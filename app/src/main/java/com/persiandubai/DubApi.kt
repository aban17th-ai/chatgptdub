
package com.persiandubai

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

data class JobStatusResponse(
    val job_id: String,
    val status: String,
    val progress: Int = 0,
    val message: String? = null,
    val output_url: String? = null
)

interface DubApi {

    @Multipart
    @POST("jobs")
    suspend fun createJob(
        @Part media: MultipartBody.Part,
        @Part("target_language") targetLanguage: RequestBody,
        @Part("preserve_background") preserveBackground: RequestBody,
        @Part("separate_speakers") separateSpeakers: RequestBody
    ): JobStatusResponse

    @GET("jobs/{jobId}")
    suspend fun getJob(
        @Path("jobId") jobId: String
    ): JobStatusResponse
}

fun createDubApi(baseUrl: String): DubApi {
    val retrofit = Retrofit.Builder()
        .baseUrl(
            if (baseUrl.endsWith("/")) baseUrl
            else "$baseUrl/"
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    return retrofit.create(DubApi::class.java)
}
