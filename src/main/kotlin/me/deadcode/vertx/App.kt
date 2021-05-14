package me.deadcode.vertx

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import io.vertx.core.Vertx
import io.vertx.core.logging.LoggerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext


class App : CliktCommand(), CoroutineScope {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val coroutineContext: CoroutineContext by lazy {
        Executors.newSingleThreadExecutor {
            Thread(it)
        }.asCoroutineDispatcher()
    }

    val poolSize = 5  // the smaller the faster it is depleted
    val webServerTimeout = 2500L  // number of ms to wait for a DB ping

    val port: Int by option("--port",
        help="REST port to bind to")
        .int().default(33660)

    val nclients: Int by option("--clients",
        help="Number of clients to generate")
        .int().default(500)

    val dosTime: Long by option("--dos-time",
        help="Number of ms to run all clients, after this time only one client will be run")
        .long().default(10_000L)

    val runFixed: Boolean by option("--fix",
        help="Run the server with leakage fix")
        .flag(default=false)

    val dbHost: String by option("--db-host",
        help="MySQL host to connect to")
        .default("127.0.0.1")

    val dbPort: Int by option("--db-port",
        help="DB port")
        .int().default(3306)

    val dbUser: String by option("--db-user",
        help="MySQL user")
        .default("root")

    val dbPass: String by option("--db-pass",
        help="MySQL user password")
        .default("")

    val dbDatabase: String? by option("--db-db",
        help="MySQL database to connect to. Works also without this parameter")

    lateinit var vertx: Vertx
    val threads = ArrayList<Thread>()
    val numClients = AtomicInteger(0)

    override fun run() {
        vertx = Vertx.vertx()
        vertx.deployVerticle(WebServer(vertx, this)) {
            val verticleWeb = it.result()
            logger.info("REST deployed: $verticleWeb")
            if (verticleWeb.isNullOrBlank()){
                logger.error("REST deployment failed")
            }

            startClients()
        }
    }

    fun startClients() {
        logger.info("Starting client 0")
        thread(start = true) {
            client(0, 0L)
        }.also {
            threads.add(it)
        }

        Thread.sleep(5000)
        val tstart = System.currentTimeMillis()
        for(i in 1 until nclients) {
            thread(start = true) {
                client(1, tstart)
            }.also {
                threads.add(it)
            }
        }
    }

    private fun client(idx: Int, tstart: Long = 0){
        logger.info("Starting client $idx, running: ${numClients.get()}")
        numClients.incrementAndGet()
        Thread.sleep(2000)

        while(true) {
            val ctime = System.currentTimeMillis()
            if (idx > 0 && ctime - tstart > dosTime){
                logger.info("Shutting down client $idx, running: ${numClients.get()}")
                break
            }

            val httpClient: HttpClient = HttpClientBuilder.create().build()
            val httpGet = HttpGet("http://localhost:$port/api/status")
            val response = try {
                httpClient.execute(httpGet)
            } catch (ex: IOException) {
                logger.info("Could not query ${ex.message}")
                continue
            }

            val reader = try {
                BufferedReader(InputStreamReader(response.entity.content))
            } catch (ex: IOException) {
                logger.info("Could not query ${ex.message}")
                continue
            }

            var line: String? = ""
            while (true) {
                try {
                    if (reader.readLine().also { line = it } == null) break
                } catch (ex: IOException) {
                    logger.info("Could not query ${ex.message}")
                }
                logger.info(line)
            }
        }
        numClients.decrementAndGet()
    }

    companion object {
        init {
            System.setProperty("vertx.logger-delegate-factory-class-name", "io.vertx.core.logging.SLF4JLogDelegateFactory") //::javaClass.name)
        }
    }
}


fun main(args: Array<String>) = App().main(args)
