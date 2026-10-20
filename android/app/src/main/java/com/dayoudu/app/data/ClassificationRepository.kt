package com.dayoudu.app.data

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class ClassificationRepository(private val database: NutritionDatabase) {
    private val dao = database.classificationDao()

    fun observeCategories(): Flow<List<TimeCategoryEntity>> = dao.observeCategories()
    fun observeProjects(): Flow<List<ProjectEntity>> = dao.observeProjects()

    suspend fun addCategory(name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 50) { "类别名称须为 1–50 字" }
        database.withTransaction {
            require(dao.categoryByName(clean) == null) { "类别已存在，可在列表中启用" }
            dao.insert(TimeCategoryEntity(UUID.randomUUID().toString(), clean, false))
        }
    }

    suspend fun setCategoryArchived(category: TimeCategoryEntity, archived: Boolean) {
        dao.update(category.copy(archived = archived))
    }

    suspend fun addProject(name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 80) { "项目名称须为 1–80 字" }
        dao.insert(ProjectEntity(UUID.randomUUID().toString(), clean, false))
    }

    suspend fun setProjectArchived(project: ProjectEntity, archived: Boolean) {
        dao.update(project.copy(archived = archived))
    }
}
