package com.streamiax.streaming.addon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AddonManifest(
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val resources: List<String>,
    val types: List<String>,
    val catalogs: List<CatalogSpec>,
    val idPrefixes: List<String>? = null,
    val background: String? = null,
    val logo: String? = null,
)

@Serializable
data class CatalogSpec(
    val type: String,
    val id: String,
    val name: String,
    val extra: List<ExtraSpec> = emptyList(),
)

@Serializable
data class ExtraSpec(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String>? = null,
)

@Serializable
data class CatalogResponse(
    val metas: List<MetaPreview>,
)

@Serializable
data class MetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String>? = null,
)

@Serializable
data class MetaResponse(
    val meta: MetaDetail,
)

@Serializable
data class MetaDetail(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val videos: List<VideoInfo>? = null,
)

@Serializable
data class VideoInfo(
    val id: String,
    val title: String,
    val released: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
)

@Serializable
data class StreamResponse(
    val streams: List<StreamInfo>,
)

@Serializable
data class StreamInfo(
    val url: String? = null,
    val infoHash: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("behaviorHints")
    val behaviorHints: Map<String, String>? = null,
)
