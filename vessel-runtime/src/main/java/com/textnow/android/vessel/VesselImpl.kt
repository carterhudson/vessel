/**
 * MIT License
 *
 * Copyright (c) 2020 TextNow, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.textnow.android.vessel

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.distinctUntilChanged
import androidx.lifecycle.map
import androidx.room.CoroutinesRoom
import androidx.room.Room
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.reflect.KClass

/**
 * Vessel provides a container for your data.
 * It is recommended to use one instance per `name`. Consider using DI.
 *
 * @param appContext application context
 * @param name unique name of your vessel
 * @param inMemory true if the database should be in-memory only [Default: false]
 * @param allowMainThread to allow calls to be made on the main thread. [Default: false]
 * @param callback for notifications of database state changes
 * @param cache Optional [VesselCache]. Built-ins: [DefaultCache] and [LruCache] [Default: null]
 * @param profile if true, enables profiling.  [profileData] will be non-null in this case.  [Default: false]
 */
class VesselImpl(
    private val appContext: Context,
    private val name: String = "vessel-db",
    private val inMemory: Boolean = false,
    private val allowMainThread: Boolean = false,
    private val callback: VesselCallback? = null,
    private val cache: VesselCache? = null,
    private val profile: Boolean = false,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    externalDb: VesselDb? = null,
) : Vessel {

    /**
     * Indicates a null value - use in place of actual null when caching a null value in [VesselCache]
     * Some cache implementations may not allow storing null directly (ex, a [VesselCache] built using ConcurrentHashMap)
     */
    companion object {
        internal val nullValue = object {}
    }


    // region initialization

    constructor(
        appContext: Context,
        name: String = "vessel-db",
        inMemory: Boolean = false,
        allowMainThread: Boolean = false,
        callback: VesselCallback? = null,
        cache: VesselCache? = null,
    ) : this(appContext, name, inMemory, allowMainThread, callback, cache, false)

    /**
     * Gson instance used for serializing data objects into the database
     */
    private val gson: Gson = GsonBuilder()
        .enableComplexMapKeySerialization()
        // other configuration
        .create()

    private val db: VesselDb = externalDb ?: when (inMemory) {
        true -> Room.inMemoryDatabaseBuilder(appContext, VesselDb::class.java)
        false -> Room.databaseBuilder(appContext, VesselDb::class.java, name)
    }
        .apply {
            enableMultiInstanceInvalidation()
            if (allowMainThread) {
                allowMainThreadQueries()
            }
            callback?.let {
                addCallback(it)
            }
            // Example:
//                addMigrations(VesselMigration(1,2){ migration, db ->
//                    logd("migrating from ${migration.startVersion} -> ${migration.endVersion}")
//                })
        }
        .build()

    /**
     * Room DAO
     */
    private val dao: VesselDao = db.vesselDao()

    /**
     * As opposed to db.isOpen, this keeps track of whether you called a method after calling close().
     * Useful for debugging unit tests.
     */
    private var closeWasCalled: Boolean = false

    private val profiler: Profiler = when {
        profile -> ProfilerImpl()
        else -> DummyProfiler()
    }

    /**
     * Core preload implementation - is the same for blocking or suspend calls
     * This is similar to what the code generated by Room does for both cases
     *
     * @return true only if the entire database could be loaded into cache in the allotted timeout
     */
    private fun preloadImpl(timeoutMS: Int?): PreloadReport {
        val cursor = db.query("SELECT * FROM vessel", emptyArray())

        cursor.use {
            val typeCol = cursor.getColumnIndex("type")
            val dataCol = cursor.getColumnIndex("data")

            val startTimeMS = System.currentTimeMillis()
            val report = PreloadReport()

            while (cursor.moveToNext()) {
                val type = cursor.getString(typeCol)
                val data = cursor.getString(dataCol)

                val kclass = try {
                    Class.forName(type).kotlin
                } catch (error: Exception) {
                    report.missingTypes.add(type)
                    report.errorsOcurred = true
                    profiler.countBlocking(Event.TYPE_NOT_FOUND)
                    continue
                }

                val obj = try {
                    if (data != null) fromJson(data, kclass) else nullValue
                } catch (error: Exception) {
                    report.deserializationErrors.add(type)
                    report.errorsOcurred = true
                    continue
                }

                cache?.set(type, obj as Any)

                timeoutMS?.let {
                    val duration = System.currentTimeMillis() - startTimeMS
                    if (duration > it) {
                        report.timedOut = true
                        profiler.countBlocking(Event.PRELOAD_TIMEOUT)
                        return report
                    }
                }
            }

            return report
        }
    }

    override suspend fun preload(timeoutMS: Int?): PreloadReport {
        if (cache == null) {
            return PreloadReport(errorsOcurred = true)
        }

        return profiler.time(Span.PRELOAD_FROM_DB) {
            /**
             * This is exactly the same pattern that the Room code generator emits/
             * Implementing this way so preloading can bail out if a time limit is hit
             *
             * Note:  This may run on a different dispatcher if running in a transaction, meaning
             * the profiling may be a little off with respect to which coroutine is doing the work.
             * Deeming this as acceptable - the profiled execution time is still valid
             */
            CoroutinesRoom.execute(db, false) {
                preloadImpl(timeoutMS)
            }
        }
    }

    override fun preloadBlocking(timeoutMS: Int?): PreloadReport {
        if (cache == null) {
            return PreloadReport(errorsOcurred = true)
        }

        return profiler.timeBlocking(Span.PRELOAD_FROM_DB) {
            preloadImpl(timeoutMS)
        }
    }

    // endregion

    // region profiling

    override val profileData
        get() = if (DummyProfiler::class.isInstance(profiler)) {
            null
        } else {
            profiler.snapshot
        }

    // endregion

    // region helper functions

    /**
     * Convert a stored json string into the specified data type.
     */
    private fun <T : Any> fromJson(value: String, type: KClass<T>): T? {
        try {
            return gson.fromJson(value, type.java)
        } catch (error: Exception) {
            profiler.countBlocking(Event.DESERIALIZATION_ERROR)
            throw error
        }
    }

    /**
     * Convert a specified data type into a json string for storage.
     */
    private fun <T : Any> toJson(value: T) = gson.toJson(value)

    /**
     * Get the type of the specified data.
     */
    @VisibleForTesting
    override fun <T : Any> typeNameOf(value: T): String {
        val name = value.javaClass.kotlin.qualifiedName

        if (name == null) {
            profiler.countBlocking(Event.TYPE_NOT_FOUND)
            throw AssertionError("anonymous classes not allowed. their names will change if the parent code is changed.")
        }

        return name
    }

    fun<T:Any> qualifiedNameOf(type: KClass<T>): String? {
        val name = type.qualifiedName

        if (name == null) {
            profiler.countBlocking(Event.TYPE_NOT_FOUND)
        }

        return name
    }

    /**
     * Close the database instance.
     */
    @VisibleForTesting
    override fun close() {
        db.close()
        callback?.onClosed?.invoke()
        closeWasCalled = true
    }

    // endregion

    // region cache helpers

    /**
     * Looks up a type in the cache
     *
     * Returns a [Pair], where [Pair.first] indicates if the type was found in the cache and
     * [Pair.second] contains its value.
     *
     * null is a valid value for [Pair.second] and indicates that the type is known to not exist
     * in the database.  This can be used to avoid going to the database to determine if the value exists
     */
    private fun <T : Any> findCached(type: KClass<T>): Pair<Boolean, T?> {
        // omitting use of qualifiedNameOf to avoid double-counting the TYPE_NOT_FOUND event
        val typeName = type.qualifiedName ?: return Pair(false, null)

        return when (val lookup = cache?.get<T>(typeName)) {
            nullValue -> Pair(true, null)
            null -> Pair(false, null)
            else -> Pair(true, lookup)
        }
    }

    /**
     * Returns true if passed data already exists in the cache, which must mean the same
     * value already exists in the database
     *
     * This can be used to avoid rewriting the same data back to the database.
     *
     * For optimal performance [T] should implement [Object.equals]. Types not implementing equals
     * cannot be compared for equality, meaning all writes for that type will always go to the database
     */
    private fun <T : Any> inCache(data: T): Boolean {
        val (exists, value) = findCached(data.javaClass.kotlin)

        if (exists && value == data) {
            return true
        }

        return false
    }
    // endregion

    // region blocking accessors

    /**
     * Get the data of a given type.
     *
     * @param type of data class to lookup
     * @return the data, or null if it does not exist
     */
    override fun <T : Any> getBlocking(type: KClass<T>): T? {
        check(!closeWasCalled) { "Vessel($name:${hashCode()}) was already closed." }

        val (exists, value) = findCached(type)
        if (exists) {
            profiler.countBlocking(Event.CACHE_HIT_READ)
            return value
        }

        val typeName = qualifiedNameOf(type) ?: return null

        return profiler.timeBlocking(Span.READ_FROM_DB) {
            dao.getBlocking(typeName)
        }?.data.let {
            val data = if (it != null) fromJson(it, type) else null
            cache?.set(typeName, data ?: nullValue)
            data
        } ?: run {
            cache?.set(typeName, nullValue)
            null
        }
    }

    /**
     * Get the data of a given type.
     *
     * @param type of data class to lookup
     * @return the data, or null if it does not exist
     */
    override fun <T : Any> getBlocking(type: Class<T>) = getBlocking(type.kotlin)


    /**
     * Set the specified data.
     *
     * @param value of the data class to set/replace.
     */
    override fun <T : Any> setBlocking(value: T) {
        check(!closeWasCalled) { "Vessel($name:${hashCode()}) was already closed." }

        if (inCache(value)) {
            profiler.countBlocking(Event.CACHE_HIT_WRITE)
            return
        }

        typeNameOf(value).let {
            profiler.timeBlocking(Span.WRITE_TO_DB) {
                dao.setBlocking(
                    entity = VesselEntity(
                        type = it,
                        data = toJson(value)
                    )
                )
            }
            cache?.set(it, value)
        }
    }

    /**
     * Delete the specified data.
     *
     * @param type of the data class to remove.
     */
    override fun <T : Any> deleteBlocking(type: KClass<T>) {
        check(!closeWasCalled) { "Vessel($name:${hashCode()}) was already closed." }

        val (exists, value) = findCached(type)

        if (exists && value == null) {
            profiler.countBlocking(Event.CACHE_HIT_DELETE)
            return
        }

        qualifiedNameOf(type)?.let { typeName ->
            profiler.timeBlocking(Span.DELETE_FROM_DB) {
                dao.deleteBlocking(typeName)
            }
            cache?.set(typeName, nullValue)
        }
    }

    /**
     * Delete the specified data.
     *
     * @param type of the data class to remove.
     */
    override fun <T : Any> deleteBlocking(type: Class<T>) = deleteBlocking(type.kotlin)

    // endregion

    // region suspend accessors

    /**
     * Get the data of a given type, in a suspend function.
     *
     * @param type of data class to lookup
     * @return the data, or null if it does not exist
     */
    override suspend fun <T : Any> get(type: KClass<T>): T? = withContext(dispatcher) {
        check(!closeWasCalled) { "Vessel($name:${hashCode()}) was already closed." }

        val (exists, value) = findCached(type)

        if (exists) {
            profiler.count(Event.CACHE_HIT_READ)
            return@withContext value
        }

        val typeName = qualifiedNameOf(type) ?: return@withContext null

        return@withContext profiler.time(Span.READ_FROM_DB) {
            dao.get(typeName)
        }?.data?.let { entity ->
            fromJson(entity, type).also { data ->
                cache?.set(typeName, data ?: nullValue)
            }
        } ?: run {
            cache?.set(typeName, nullValue)
            null
        }
    }


    /**
     * Set the specified data, in a suspend function.
     *
     * @param value of the data class to set/replace.
     */
    override suspend fun <T : Any> set(value: T): Unit = withContext(dispatcher) {
        check(!closeWasCalled) { "Vessel($name:${hashCode()}) was already closed." }

        if (inCache(value)) {
            profiler.count(Event.CACHE_HIT_WRITE)
            return@withContext
        }

        typeNameOf(value).let { typeName ->
            profiler.time(Span.WRITE_TO_DB) {
                dao.set(
                    entity = VesselEntity(
                        type = typeName,
                        data = toJson(value)
                    )
                )
            }
            cache?.set(typeName, value)
        }
    }

    /**
     * Delete the specified data, in a suspend function.
     *
     * @param type of the data class to remove.
     */
    override suspend fun <T : Any> delete(type: KClass<T>): Unit = withContext(dispatcher) {
        check(!closeWasCalled) { "Vessel($name:${hashCode()}) was already closed." }

        val (exists, value) = findCached(type)

        if (exists && value == null) {
            profiler.count(Event.CACHE_HIT_DELETE)
            return@withContext
        }

        qualifiedNameOf(type)?.let { typeName ->
            profiler.time(Span.DELETE_FROM_DB) {
                dao.delete(typeName)
            }
            cache?.set(typeName, nullValue)
        }
    }

    // endregion

    // region utilities

    /**
     * Replace one data class with another, in a suspending transaction.
     *
     * @param old data model to remove
     * @param new data model to add
     */
    @Deprecated(
        message = "replacing by passing in an object will be removed in a future version in favour of using class type",
        replaceWith = ReplaceWith("replace(oldType = old::class, new = new)"),
    )
    override suspend fun <OLD : Any, NEW : Any> replace(old: OLD, new: NEW) {
        replace(old::class, new)
    }

    /**
     * Replace one data with another, in a suspending transaction.
     *
     * @param oldType of data model to remove
     * @param new data model to add
     */
    override suspend fun <OLD : Any, NEW : Any> replace(
        oldType: KClass<OLD>,
        new: NEW
    ): Unit = withContext(dispatcher) {
        check(!closeWasCalled) { "Vessel($name:${hashCode()}) was already closed." }

        val newName = typeNameOf(new)

        qualifiedNameOf(oldType)?.let { oldName ->
            if (oldName == newName) {
                set(new)
            } else {
                val (oldExists, oldValue) = findCached(oldType)
                if (inCache(new) && oldExists && oldValue == null) {
                    profiler.count(Event.CACHE_HIT_REPLACE)
                    return@let
                }

                profiler.time(Span.REPLACE_IN_DB) {
                    dao.replace(
                        oldType = oldName,
                        new = VesselEntity(
                            type = newName,
                            data = toJson(new)
                        )
                    )
                }

                /** Note - caching the result of the replace is safe, as any transactional Dao calls will throw on failure
                 * This prevents the cache from getting out of sync with the database
                 * This can be seen by decompiling a generated Room Dao, or somewhat by checking the Room source code generator
                 * (https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:room/)
                 */
                cache?.set(oldName, nullValue)
                cache?.set(newName, new)
            }
        }
    }

    /**
     * Clear the database.
     */
    override fun clear() {
        check(!closeWasCalled) { "Vessel($name:${hashCode()}) was already closed." }

        cache?.clear()

        profiler.timeBlocking(Span.CLEAR_DB) {
            db.clearAllTables()
        }
    }

    // endregion


    // region observers

    /**
     * Observe the distinct values of a given type, as a flow.
     *
     * @param type of data class to lookup
     * @return flow of the values associated with that type
     */
    override fun <T : Any> flow(type: KClass<T>): Flow<T?> {
        check(!closeWasCalled) { "Vessel($name:${hashCode()}) was already closed." }
        qualifiedNameOf(type)?.let { typeName ->
            return dao.getFlow(typeName)
                .distinctUntilChanged()
                .map {
                    it?.data?.let { entity ->
                        val data = fromJson(entity, type)
                        cache?.set(typeName, data as Any)
                        data
                    }
                }
        }
        return emptyFlow()
    }

    /**
     * Observe the distinct values of a given type, as a livedata.
     *
     * @param type of data class to lookup
     * @return livedata of the values associated with that type
     */
    override fun <T : Any> livedata(type: KClass<T>): LiveData<T?> {
        check(!closeWasCalled) { "Vessel($name:${hashCode()}) was already closed." }
        qualifiedNameOf(type)?.let { typeName ->
            return dao.getLiveData(typeName)
                .distinctUntilChanged()
                .map {
                    it?.data?.let { entity ->
                        val data = fromJson(entity, type)
                        cache?.set(typeName, data as Any)
                        data
                    }
                }
        }
        return MutableLiveData<T>()
    }

    // endregion
}
