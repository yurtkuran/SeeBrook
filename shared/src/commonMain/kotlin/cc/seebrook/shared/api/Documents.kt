package cc.seebrook.shared.api

import kotlinx.serialization.Serializable

@Serializable
data class DocumentCategoryResponse(val id: String, val name: String, val documentCount: Int)

@Serializable
data class DocumentCategoriesResponse(val categories: List<DocumentCategoryResponse>)

/** A published HOA document. Dates are ISO-8601: `documentDate` a date, `uploadedAt` an instant. */
@Serializable
data class DocumentResponse(
    val id: String,
    val title: String,
    val description: String,
    val categoryId: String,
    val categoryName: String,
    val tags: List<String>,
    val documentDate: String? = null,
    val uploadedAt: String,
    val pageCount: Int,
    val sizeBytes: Long,
)

@Serializable
data class DocumentsPageResponse(
    val documents: List<DocumentResponse>,
    val page: Int,
    val pageSize: Int,
    val total: Int,
)

/** A short-lived link to the PDF; fetch it before `expiresAt` and do not store it. */
@Serializable
data class DownloadLinkResponse(val url: String, val expiresAt: String, val fileName: String)
