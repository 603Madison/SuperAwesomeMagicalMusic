package com.music.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/** An error to show the person: [message] is safe to send to them as it is. */
class ApiError(val status: HttpStatusCode, message: String) : Exception(message) {
    companion object {
        fun badRequest(message: String) = ApiError(HttpStatusCode.BadRequest, message)
        fun unauthorized(message: String = "Sign in first") = ApiError(HttpStatusCode.Unauthorized, message)
        fun forbidden(message: String = "That needs an admin") = ApiError(HttpStatusCode.Forbidden, message)
        fun notFound(message: String = "Not found") = ApiError(HttpStatusCode.NotFound, message)
        fun conflict(message: String) = ApiError(HttpStatusCode.Conflict, message)
        fun tooMany(message: String) = ApiError(HttpStatusCode.TooManyRequests, message)
        fun upstream(message: String) = ApiError(HttpStatusCode.BadGateway, message)
    }
}

@Serializable
data class ErrorBody(val error: String)
