
package com.persiandubai

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Streaming

data class JobStatusResponse(
    val job_id: String,
    val status: String,
    val progress: Int = 0,
    val message: String = "",
    val output_url: String? = null
)

interface DubApi {

    @Multipart
    @POST("v1/jobs")
    suspend fun createJob(
        @Part media: MultipartBody.Part,
        @Part("target_language") targetLanguage: RequestBody,
        @Part("preserve_background") preserveBackground: RequestBody,
        @Part("separate_speakers") separateSpeakers: RequestBody
    ): JobStatusResponse

    @GET("v1/jobs/{jobId}")
    suspend fun getJob(
        @Path("jobId") jobId: String
    ): JobStatusResponse

    @Streaming
    @GET("v1/jobs/{jobId}/download")
    suspend fun downloadJob(
        @Path("jobId") jobId: String
    ): ResponseBody
}

fun createDubApi(baseUrl: String): DubApi {

    val normalizedUrl = if (baseUrl.endsWith("/")) {
        baseUrl
    } else {
        "$baseUrl/"
    }

    val retrofit = Retrofit.Builder()
        .baseUrl(normalizedUrl)
        .addConverterFactory(
            GsonConverterFactory.create()
        )
        .build()

    return retrofit.create(DubApi::class.java)
}
