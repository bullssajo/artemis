package com.artemis

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ArtemisApplication

fun main(args: Array<String>) {
    runApplication<ArtemisApplication>(*args)
}
