package com.dayoudu.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "time_categories", indices = [Index(value = ["name"], unique = true)])
data class TimeCategoryEntity(@PrimaryKey val id: String, val name: String, val archived: Boolean)

@Entity(tableName = "projects", indices = [Index(value = ["name"], unique = true)])
data class ProjectEntity(@PrimaryKey val id: String, val name: String, val archived: Boolean)

@Entity(tableName = "activity_tags", primaryKeys = ["activityId", "name"], indices = [Index("activityId")])
data class ActivityTagEntity(val activityId: String, val name: String)

@Dao
interface ClassificationDao {
    @Query("SELECT * FROM time_categories ORDER BY archived, name")
    fun observeCategories(): Flow<List<TimeCategoryEntity>>

    @Query("SELECT * FROM projects ORDER BY archived, name")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM time_categories WHERE name = :name LIMIT 1")
    suspend fun categoryByName(name: String): TimeCategoryEntity?

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun project(id: String): ProjectEntity?

    @Insert suspend fun insert(category: TimeCategoryEntity)
    @Insert suspend fun insert(project: ProjectEntity)
    @Insert suspend fun insert(tags: List<ActivityTagEntity>)
    @Update suspend fun update(category: TimeCategoryEntity)
    @Update suspend fun update(project: ProjectEntity)
}
