package de.miraculixx.api.stats

import de.miraculixx.api.data.DatabaseCredentials
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.delay
import org.slf4j.Logger
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.Duration.Companion.seconds

abstract class DatabaseStructure(
    val logger: Logger,
    val credentials: DatabaseCredentials
) {
    private var connection: Connection = connect()

    private fun connect(): Connection {
        val con = DriverManager.getConnection(credentials.url, credentials.user, credentials.password)
        if (con.isValid(0))
            logger.info(">> Connection established to MariaDB")
        else logger.warn(">> ERROR > MariaDB refused the connection")
        return con
    }

    @OptIn(InternalAPI::class)
    suspend inline fun call(statement: String, arguments: PreparedStatement.() -> Unit): ResultSet =
        buildStatement(statement).apply(arguments).executeQuery()

    @InternalAPI
    suspend fun buildStatement(statement: String): PreparedStatement {
        while (!connection.isValid(1)) {
            logger.warn("ERROR >> SQL - No valid connection!")
            connection = connect()
            delay(1.seconds)
        }
        return connection.prepareStatement(statement)
    }
}