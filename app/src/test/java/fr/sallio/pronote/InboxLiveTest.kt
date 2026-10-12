package fr.sallio.pronote

import org.junit.Assume.assumeTrue
import org.junit.Test

/** Actualités and Messagerie on the demo. `PRONOTE_DEMO=1`. */
class InboxLiveTest {
    @Test
    fun readsNewsAndDiscussions() {
        assumeTrue(System.getenv("PRONOTE_DEMO") == "1")
        val logged = Pronote.loginCredentials(UrlConnectionHttp(), "https://demo.index-education.net/pronote", "demonstration", "pronotevs", "inbox")
        val news = Inbox.news(logged, emptyMap())
        println("${news.size} actualités")
        news.take(4).forEach { println("- ${it.date} [${it.category}] ${it.title} par ${it.author} sondage=${it.survey} lu=${it.read} : ${it.content.take(80)} ${it.questions.map { q -> q.title + q.choices }}") }
        val discussions = Inbox.discussions(logged, emptyMap())
        println("${discussions.size} discussions")
        discussions.take(3).forEach { d -> println("- ${d.subject} avec ${d.with} (${d.dateLabel}) ${d.messages.size} messages : ${d.messages.lastOrNull()?.text?.take(80)}") }
    }
}
