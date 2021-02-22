package skk

import io.confluent.developer.ksqldb.reactor.ReactorClient
import io.confluent.ksql.api.client.ClientOptions
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.html.*
import kotlinx.html.dom.createHTMLDocument
import kotlinx.html.dom.serialize
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter
import org.w3c.dom.Document
import java.net.URL

@SpringBootApplication
@RestController
class WebApp(val reactorClient: ReactorClient) {

    @GetMapping("/")
    fun index(): String {
        return Html.index.serialize(true)
    }

    @GetMapping("total")
    suspend fun total() = run {
        reactorClient.executeQuery("SELECT * FROM TOTALS WHERE ONE = 1;").map { rows ->
            rows.firstOrNull()?.getLong("TOTAL")
        }.awaitFirstOrNull()
    }

    @GetMapping("/{name}")
    fun lang(@PathVariable name: String): String {
        return Html.lang(name).serialize(true)
    }

}


@Configuration
class KafkaConfigFactory {

    @Bean
    @ConditionalOnProperty(name = ["ksqldb.endpoint", "ksqldb.username", "ksqldb.password"])
    fun ksqlReactorClient(
        @Value("\${ksqldb.endpoint}") ksqldbEndpoint: URL,
        @Value("\${ksqldb.username}") ksqldbUsername: String,
        @Value("\${ksqldb.password}") ksqldbPassword: String,
    ): ReactorClient {
        val options = ClientOptions.create()
            .setHost(ksqldbEndpoint.host)
            .setPort(ksqldbEndpoint.port)
            .setUseTls(true)
            .setUseAlpn(true)
            .setBasicAuthCredentials(ksqldbUsername, ksqldbPassword)

        val reactorClient = ReactorClient.fromOptions(options)

        val stackoverflowStream = "CREATE STREAM IF NOT EXISTS STACKOVERFLOW WITH (KAFKA_TOPIC='mytopic', VALUE_FORMAT='JSON_SR');"
        reactorClient.executeStatement(stackoverflowStream).block()

        val stackoverflowAllStream = "CREATE STREAM IF NOT EXISTS STACKOVERFLOW_ALL AS SELECT 1 AS ONE, FAVORITE_COUNT FROM STACKOVERFLOW;"
        reactorClient.executeStatement(stackoverflowAllStream).block()

        val stackoverflowTotalsTable = "CREATE TABLE IF NOT EXISTS TOTALS AS SELECT ONE, SUM(FAVORITE_COUNT) AS TOTAL FROM STACKOVERFLOW_ALL GROUP BY ONE EMIT CHANGES;"
        reactorClient.executeStatement(stackoverflowTotalsTable, mapOf("auto.offset.reset" to "earliest")).block()

        return reactorClient
    }

}

@Configuration
class WebSocketConfig {

    @Bean
    fun simpleUrlHandlerMapping(reactorClient: ReactorClient): SimpleUrlHandlerMapping {
        return SimpleUrlHandlerMapping(mapOf(
            "/langs" to langs(reactorClient),
        ), 0)
    }

    fun langs(reactorClient: ReactorClient): WebSocketHandler {
        return WebSocketHandler { session: WebSocketSession ->
            val kafkaMessages = reactorClient.streamQuery("select * from STACKOVERFLOW EMIT CHANGES;")
            val webSocketMessages = kafkaMessages.map { message ->
                val lang = "java"
                val num = 3
                session.textMessage("$lang:$num")
            }
            session.send(webSocketMessages)
        }
    }

    @Bean
    fun webSocketHandlerAdapter(): WebSocketHandlerAdapter {
        return WebSocketHandlerAdapter()
    }

}


object Html {

    class TEMPLATE(consumer: TagConsumer<*>) :
        HTMLTag("template", consumer, emptyMap(),
            inlineTag = true,
            emptyTag = false), HtmlInlineTag

    fun FlowContent.template(block: TEMPLATE.() -> Unit = {}) {
        TEMPLATE(consumer).visit(block)
    }

    fun TEMPLATE.li(classes : String? = null, block : LI.() -> Unit = {}) {
        LI(attributesMapOf("class", classes), consumer).visit(block)
    }

    fun page(js: String, content: FlowContent.() -> Unit = {}): HTML.() -> Unit = {
        head {
            link("/webjars/bootstrap/4.5.3/css/bootstrap.min.css", LinkRel.stylesheet)
            link("/assets/index.css", LinkRel.stylesheet)
            script(ScriptType.textJavaScript) {
                src = "/assets/$js"
            }
        }
        body {
            nav("navbar fixed-top navbar-light bg-light") {
                a("/", classes = "navbar-brand") {
                    +"Serverless Kotlin Kafka"
                }
            }

            div("container-fluid") {
                content()
            }
        }
    }

    val indexHTML = page("index.js") {
        template {
            id = "total-template"
            +"Total Favorites: {{total}}"
        }

        div {
            id = "total"
        }

        ul {
            id = "recent-questions"

            template {
                id = "recent-questions-template"

                li {
                    id = "lang-{{lang}}"

                    a("{{lang}}") {
                        +"{{lang}} = {{num}}"
                    }
                }
            }
        }
    }

    val index: Document = createHTMLDocument().html(block = indexHTML)

    fun langHTML(name: String) = page("lang.js") {
        +"Questions For `$name`"

        ul {
            id = "questions"

            template {
                id = "question-template"

                li {
                    a("{{url}}") {
                        +"{{title}}"
                    }
                    +" (favorites: {{favorite_count}}, views: {{view_count}})"
                }
            }
        }
    }

    fun lang(name: String): Document = createHTMLDocument().html(block = langHTML(name))

}

fun main(args: Array<String>) {
    runApplication<WebApp>(*args)
}
