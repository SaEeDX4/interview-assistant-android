package com.interviewassistant.core.live

import com.interviewassistant.core.languages.LanguageDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror of `InterviewAssistant.Tests/MultilingualLiveEvaluationTests.cs` — the fixture/language-detection case.
 * `LiveAnswersInAllTenLanguages` needs OPENAI_API_KEY + InterviewEngine + OpenAiChatAnswerProvider and is
 * deferred by name to Package D (Orchestration) in P3-PORT-NOTES.md.
 */
class MultilingualLiveEvaluationTests {

    companion object {
        /** 10 languages × 7 question types, verbatim from the C# fixture table. */
        val Questions: Map<String, List<String>> = linkedMapOf(
            "en" to listOf("Tell me about a time you led a team through a difficult change.", "How would you design an API rate limit?", "How do you prioritise a product backlog?", "Tell me about your experience at Arzif.", "How would you increase adoption of a new loyalty token?", "Why?", "Have you personally built a financial ledger?"),
            "es" to listOf("Cuénteme una situación en la que lideró a un equipo en un cambio difícil.", "¿Cómo diseñaría un límite de uso para una API?", "¿Cómo prioriza el backlog de un producto?", "Hábleme de su experiencia en Arzif.", "¿Cómo aumentaría la adopción de un nuevo token de fidelidad?", "¿Por qué?", "¿Ha construido usted personalmente un libro contable financiero?"),
            "fr" to listOf("Parlez-moi d'une fois où vous avez mené une équipe à travers un changement difficile.", "Comment concevriez-vous une limite de débit pour une API ?", "Comment priorisez-vous un backlog produit ?", "Parlez-moi de votre expérience chez Arzif.", "Comment augmenteriez-vous l'adoption d'un nouveau jeton de fidélité ?", "Pourquoi ?", "Avez-vous personnellement construit un registre financier ?"),
            "de" to listOf("Erzählen Sie von einer Situation, in der Sie ein Team durch eine schwierige Veränderung geführt haben.", "Wie würden Sie ein Rate-Limit für eine API entwerfen?", "Wie priorisieren Sie ein Produkt-Backlog?", "Erzählen Sie von Ihrer Erfahrung bei Arzif.", "Wie würden Sie die Nutzung eines neuen Loyalty-Tokens steigern?", "Warum?", "Haben Sie persönlich ein Finanz-Ledger aufgebaut?"),
            "pt" to listOf("Conte-me sobre uma vez em que você liderou uma equipe em uma mudança difícil.", "Como você projetaria um limite de taxa para uma API?", "Como você prioriza um backlog de produto?", "Fale sobre sua experiência na Arzif.", "Como você aumentaria a adoção de um novo token de fidelidade?", "Por quê?", "Você já construiu pessoalmente um razão financeiro?"),
            "it" to listOf("Mi parli di una volta in cui ha guidato un team in un cambiamento difficile.", "Come progetterebbe un limite di frequenza per un'API?", "Come assegna le priorità a un backlog di prodotto?", "Mi parli della sua esperienza in Arzif.", "Come aumenterebbe l'adozione di un nuovo token fedeltà?", "Perché?", "Ha mai costruito personalmente un registro contabile finanziario?"),
            "ar" to listOf("حدثني عن مرة قدت فيها فريقًا خلال تغيير صعب.", "كيف ستصمم حدًا لمعدل الطلبات في واجهة API؟", "كيف تحدد أولويات قائمة مهام المنتج؟", "حدثني عن خبرتك في Arzif.", "كيف ستزيد اعتماد رمز ولاء جديد؟", "لماذا؟", "هل قمت شخصيًا ببناء دفتر أستاذ مالي؟"),
            "fa" to listOf("درباره زمانی بگویید که تیمی را در یک تغییر دشوار رهبری کردید.", "چطور برای یک API محدودیت نرخ طراحی می‌کنید؟", "چطور بک‌لاگ محصول را اولویت‌بندی می‌کنید؟", "درباره تجربه‌تان در Arzif بگویید.", "چطور پذیرش یک توکن وفاداری جدید را افزایش می‌دهید؟", "چرا؟", "آیا شخصاً یک دفتر کل مالی ساخته‌اید؟"),
            "zh" to listOf("请讲一次你带领团队度过艰难变革的经历。", "你会如何设计API的限流？", "你如何确定产品待办事项的优先级？", "请谈谈你在Arzif的经历。", "你会如何提高新忠诚度代币的使用率？", "为什么？", "你亲自搭建过财务账本系统吗？"),
            "hi" to listOf("मुझे उस समय के बारे में बताइए जब आपने किसी कठिन बदलाव में टीम का नेतृत्व किया।", "आप किसी API के लिए रेट लिमिट कैसे डिज़ाइन करेंगे?", "आप प्रोडक्ट बैकलॉग को प्राथमिकता कैसे देते हैं?", "Arzif में अपने अनुभव के बारे में बताइए।", "आप एक नए लॉयल्टी टोकन को अपनाने की दर कैसे बढ़ाएंगे?", "क्यों?", "क्या आपने व्यक्तिगत रूप से कोई वित्तीय लेजर बनाया है?"),
        )
    }

    @Test
    fun fixtureCoversTenLanguagesAndDetectsEachQuestionLanguage() {
        assertEquals(10, Questions.size)
        for ((lang, qs) in Questions) {
            assertEquals(7, qs.size)
            // Full questions (not the 1-word follow-up) must be detected as their language.
            for (q in qs.filter { it.length > 20 }) {
                assertTrue("$lang: \"$q\" detected as ${LanguageDetector.detect(q)}", lang == LanguageDetector.detect(q).code)
            }
        }
    }
}
