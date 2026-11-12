package com.shotagent.mark

sealed class ApiCall<out T> {
    data class Ok<T>(val value: T) : ApiCall<T>()
    data class Err(val message: String) : ApiCall<Nothing>()
}
