package me.deadcode.vertx

import com.mchange.v2.c3p0.ComboPooledDataSource
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.jdbc.JDBCClient
import io.vertx.ext.jdbc.impl.JDBCClientImpl
import io.vertx.ext.sql.SQLConnection
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.ResponseContentTypeHandler
import io.vertx.ext.web.handler.TimeoutHandler
import io.vertx.kotlin.core.http.listenAwait
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.ext.sql.getConnectionAwait
import io.vertx.kotlin.ext.sql.queryAwait
import kotlinx.coroutines.*
import java.lang.invoke.MethodHandles
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.*
import javax.sql.DataSource
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.properties.Delegates

class WebServer(val vertx_: Vertx, val app: App): CoroutineVerticle() {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val rand = Random(0)

    private var server: HttpServer? = null
    private lateinit var cscope: CoroutineScope
    private var router: Router by Delegates.notNull()
    private lateinit var dbClient: JDBCClient

    override suspend fun start() {
        cscope = CoroutineScope(coroutineContext + SupervisorJob())

        // Open DB connection
        initDb()

        // Server start
        startServer()
    }

    private fun initDb() {
        val cfg = json { obj (
            "url" to "jdbc:mysql://${app.dbHost}:${app.dbPort}/${app.dbDatabase ?: ""}?autoReconnect=true&autoReconnectForPools=true&socketTimeout=5000&connectTimeout=2000&maxPoolSize=${app.poolSize}",
            "user" to app.dbUser,
            "password" to app.dbPass,
            "max_pool_size" to app.poolSize,
            "driver_class" to "com.mysql.cj.jdbc.Driver"
        )}

        dbClient = JDBCClient.createShared(vertx, cfg)
    }

    fun getServerOptions(): HttpServerOptions {
        return HttpServerOptions()
            .setIdleTimeout(20)
            .setTcpKeepAlive(true)
    }

    private suspend fun startServer(){
        val options = getServerOptions()
        val cserver = vertx.createHttpServer(options)
        val port = app.port
        server = cserver
        initHooks()

        cserver.listenAwait(port)
        logger.info("Web Server listening @ $port")
    }

    fun initHooks(){
        router = Router.router(vertx)
        router.route("/*").handler(TimeoutHandler.create(12_000))
        router.route("/api/*").handler(ResponseContentTypeHandler.create())
        router
            .route("/api/status")
            .produces("application/json")
            .handler(BodyHandler.create())
            .handler { ctx ->
                // logger.info("status access ${ctx.request().remoteAddress()}")
                onGlobalCtxAsync { handleStatus(ctx) }
            }

        server?.requestHandler(router)
        logger.info("REST hooks initialized, server $server")
    }

    private suspend fun handleStatus(ctx: RoutingContext){
        val response = ctx.response().apply {
            isChunked = true
        }

        val jsResp = JsonObject()
        try {
            //withTimeout(rand.nextLong().rem(1000) + 1) {
            withTimeout(app.webServerTimeout) {
                if (app.runFixed){
                    pingWithFix()
                } else {
                    pingWithBug()
                }
            }

            logger.info("Ping success, #cl: ${app.numClients.get()}, ${getConnectionPoolSize()}")
            jsResp.put("success", 1)

        } catch (e: Exception){
            logger.info("Error: $e, #cl: ${app.numClients.get()}, ${getConnectionPoolSize()}")
            jsResp.put("success", 0)
            jsResp.put("error", e.localizedMessage)
            response.statusCode = 503
        }

        response.isChunked = true
        response.putHeader("content-type", "application/json")
        response.write(jsResp.toString()).end()
    }

    private suspend fun pingWithBug(){
        dbClient.getConnectionAwait().use {
            it.queryAwait("SELECT 1")
        }
    }

    private suspend fun pingWithFix(){
        val cid = UUID.randomUUID().toString()
        var conn: SQLConnection? = null
        try {
            val tconn = suspendCancellableCoroutine { cont: CancellableContinuation<SQLConnection> ->
                try {
                    dbClient.getConnection {

                        if (!it.succeeded()){
                            logger.error("!!!!!!!!!!!!!! Should not happen")
                            runNoExc { it.result().close() }
                            throw RuntimeException("Connection not resolved, $cid")
                        }

                        logger.trace("[DBCL] Get conn res: ${it.succeeded()} ${it.result()}, $cid")
                        val res = it.result()
                        if (cont.isActive) {
                            conn = res
                            cont.resume(res)
                        } else {
                            logger.trace("[DBCL] Get conn cancelled, free, $cid")
                            res.close()
                        }
                    }
                } catch (e: Exception) {
                    logger.trace("Exception in get conn, $cid")
                    cont.resumeWithException(e)
                }
            }

            tconn.queryAwait("SELECT 1")

        } catch (e: CancellationException) {
            logger.trace("[DBCL] Cancelled $conn, $cid", e)
            throw e
        } catch (e: Exception) {
            logger.trace("[DBCL] Another exc $conn, $cid", e)
            throw e
        } finally {
            logger.trace(".. finally, $conn, $cid")
            conn?.close {
                logger.trace(".. finally closing, $conn, $cid")
            }
        }
    }

    inline fun <R> runNoExc(action: () -> R): R? = try {
        action()
    } catch (e: Throwable) {
        null
    }

    private fun onGlobalCtxAsync(runner: suspend CoroutineScope.() -> Unit): Job {
        return cscope.launch(cscope.coroutineContext, CoroutineStart.DEFAULT) { supervisorScope { runner.invoke(cscope) } }
    }

    /**
     * Hack method to determine size of the pool (busy / idle)
     */
    private fun getConnectionPoolSize(): String? {
        return try {
            val ds = getDataSource()
            return (ds as? ComboPooledDataSource)?.let {
                "++ C3P0 conn: busy: ${it.numBusyConnections}, ${it.numBusyConnectionsAllUsers}; " +
                        "idle: ${it.numIdleConnections}, ${it.numIdleConnectionsAllUsers}, " +
                        "${Thread.currentThread()}"
            }
        } catch(e: Exception){
            ""
        }
    }

    private fun getDataSource(): DataSource? {
        if (dbClient !is JDBCClientImpl) {
            return null
        }

        val lookup = MethodHandles.privateLookupIn(Field::class.java, MethodHandles.lookup())
        val zModifiers = lookup.findVarHandle(Field::class.java, "modifiers", Int::class.javaPrimitiveType)

        val zGetDataSourceHolder = io.vertx.ext.jdbc.impl.JDBCClientImpl::class.java.declaredMethods.find { it.name == "getDataSourceHolder" } ?: throw RuntimeException("Not found")
        zGetDataSourceHolder.isAccessible = true

        val zDataSourceHolder = Class.forName("io.vertx.ext.jdbc.impl.DataSourceHolder")
        val zDataSource = zDataSourceHolder.declaredFields.find { it.name == "dataSource" } ?: throw RuntimeException("dataSource not found")
        zDataSource.isAccessible = true

        val zHolders = io.vertx.ext.jdbc.impl.JDBCClientImpl::class.java.declaredFields.find { it.name == "holders" } ?: throw RuntimeException("holders not found")
        zHolders.isAccessible = true
        zModifiers.set(zHolders, zHolders.modifiers.and(Modifier.FINAL))
        val holders = zHolders.get(dbClient) as Map<*, *>

        val dataSourceHolder = holders.values.first()
        val dsource = zDataSource.get(dataSourceHolder) as? DataSource
        return dsource
    }
}
