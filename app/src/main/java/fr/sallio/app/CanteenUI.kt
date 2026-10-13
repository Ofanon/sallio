package fr.sallio.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/** The canteen on screen: the home card, its sheet (card QR code, balance, bookings) and the TurboSelf login. */
object CanteenUI {

    private val fr = Locale.FRANCE
    private const val ESPACE = "https://espacenumerique.turbo-self.com"

    /** The lunch that matters now: today's until 13h, then the next one TurboSelf lists. */
    private fun nextLunch(d: Canteen.Data, now: LocalDateTime): Canteen.Day? {
        val from = if (now.hour < 13) now.toLocalDate() else now.toLocalDate().plusDays(1)
        return d.days.firstOrNull { it.date >= from && it.booking.allowed } ?: d.days.firstOrNull { it.date >= from }
    }

    private fun dayName(date: LocalDate, today: LocalDate) = when (date) {
        today -> "Aujourd'hui"
        today.plusDays(1) -> "Demain"
        else -> "${Agenda.dayName(date)} ${date.dayOfMonth}"
    }

    // ---- Home card -------------------------------------------------------------------

    fun homeCard(activity: MainActivity, now: LocalDateTime): View? {
        val ctx = activity
        val canteen = Canteen.get(ctx)
        if (!canteen.connected()) return null
        val d = canteen.data()
        val card = UI.card(ctx, padding = 14)
        val row = UI.row(ctx)
        row.addView(FrameLayout(ctx).apply {
            background = UI.rounded(ctx.getColor(R.color.accent), ctx.dp(12).toFloat())
            addView(UI.icon(ctx, R.drawable.ic_coin, R.color.on_accent, 20).apply {
                layoutParams = FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER)
            })
        }, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
        row.addView(UI.hgap(ctx, 12))
        val col = UI.column(ctx)
        val balance = d?.balance
        col.addView(UI.title(ctx, balance?.let { Canteen.money(it.estimatedAmount) } ?: "Cantine", 18f))
        val sub = listOfNotNull(
            d?.lunchesLeft?.let { if (it > 1) "$it repas" else if (it == 1) "1 repas" else "à recharger" },
            d?.let { nextLunch(it, now) }?.let { day ->
                val name = dayName(day.date, now.toLocalDate()).let { if (it == "Aujourd'hui" || it == "Demain") "$it midi" else it }
                when {
                    day.booking.booked > 0 -> "$name : réservé"
                    !day.booking.allowed -> "$name : ${day.booking.message ?: "pas de réservation"}"
                    else -> "$name : pas réservé"
                }
            },
            if (d == null) "Chargement…" else null,
        ).joinToString(" · ")
        col.addView(UI.oneLine(UI.text(ctx, sub, 13f, R.color.text2)).apply { setPadding(0, ctx.dp(2), 0, 0) })
        canteen.error()?.let { col.addView(UI.oneLine(UI.text(ctx, it, 12f, R.color.alert)).apply { setPadding(0, ctx.dp(2), 0, 0) }) }
        row.addView(col, UI.weight())
        if (d?.cardNumber != null) {
            row.addView(UI.text(ctx, "Ma carte", 13f, R.color.on_accent, 800).apply {
                background = UI.sticker(ctx, ctx.getColor(R.color.accent), ctx.getColor(R.color.accent_edge), 12, 2)
                setPadding(ctx.dp(12), ctx.dp(7), ctx.dp(12), ctx.dp(9))
                setOnClickListener { Motion.tap(this); showCard(activity, d) }
            })
        }
        card.addView(row, UI.matchWidth())
        card.setOnClickListener { Motion.tap(card); showSheet(activity) }
        return card
    }

    // ---- QR code ---------------------------------------------------------------------

    /** The card number as a QR code, black on white whatever the theme: terminals read that best. */
    private fun qrView(activity: MainActivity, number: Long, sizeDp: Int): View {
        val ctx = activity
        val qr = QrCode.encode(number.toString())
        val bitmap = Bitmap.createBitmap(qr.size, qr.size, Bitmap.Config.ARGB_8888)
        for (y in 0 until qr.size) for (x in 0 until qr.size) bitmap.setPixel(x, y, if (qr.modules[y][x]) Color.BLACK else Color.WHITE)
        return FrameLayout(ctx).apply {
            background = UI.rounded(Color.WHITE, ctx.dp(18).toFloat())
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
            addView(ImageView(ctx).apply {
                setImageBitmap(bitmap)
                // Sharp modules when scaled up.
                drawable.isFilterBitmap = false
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp)))
        }
    }

    /** The card alone, big and bright, for the terminal. */
    fun showCard(activity: MainActivity, d: Canteen.Data) {
        val number = d.cardNumber ?: return
        val window = activity.window
        val before = window.attributes.screenBrightness
        val dialog = Sheet.open(activity, activity.bottomInsetPx) { sheet, _ ->
            Sheet.title(sheet, "Ma carte de cantine", "À présenter à la borne · marche sans réseau")
            sheet.addView(FrameLayout(activity).apply {
                addView(qrView(activity, number, 240), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }, UI.matchWidth().apply { topMargin = activity.dp(12) })
            sheet.addView(UI.text(activity, "N° $number", 14f, R.color.text2, 800).apply {
                gravity = Gravity.CENTER
                setPadding(0, activity.dp(10), 0, 0)
            }, UI.matchWidth())
        }
        // Full brightness while the code is on screen, as for a boarding pass.
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        dialog.setOnDismissListener { window.attributes = window.attributes.apply { screenBrightness = before } }
    }

    // ---- Sheet -----------------------------------------------------------------------

    fun showSheet(activity: MainActivity) {
        val ctx = activity
        val canteen = Canteen.get(ctx)
        Sheet.open(activity, activity.bottomInsetPx) { sheet, _ ->
            val d = canteen.data()
            val now = LocalDateTime.now()
            Sheet.title(sheet, "Cantine", "Turboself · " + (d?.let { "mis à jour ${Agenda.ago(it.updatedAt, now)}" } ?: "pas encore chargé"))
            canteen.error()?.let { sheet.addView(UI.text(ctx, it, 13f, R.color.alert, 800).apply { setPadding(0, ctx.dp(6), 0, 0) }) }
            if (d == null) {
                sheet.addView(UI.text(ctx, "Tes infos de cantine arrivent à la prochaine mise à jour.", 15f, R.color.text2).apply { setPadding(0, ctx.dp(16), 0, 0) })
                return@open
            }

            // Balance.
            d.balance?.let { b ->
                val hero = UI.card(ctx, R.color.accent, R.color.accent_edge, padding = 18)
                hero.addView(UI.text(ctx, "SOLDE", 12f, R.color.on_accent, 900).apply { letterSpacing = 0.06f; alpha = 0.8f })
                hero.addView(UI.title(ctx, Canteen.money(b.estimatedAmount), 40f, R.color.on_accent))
                val lines = listOfNotNull(
                    if (b.amount != b.estimatedAmount) "${Canteen.money(b.amount)} aujourd'hui, ${Canteen.money(b.estimatedAmount)} après tes réservations" else null,
                    d.lunchesLeft?.let { "De quoi payer $it repas" + (d.lunchPrice?.let { p -> " à ${Canteen.money(p)}" } ?: "") },
                    b.estimatedMessage,
                )
                for (l in lines) hero.addView(UI.text(ctx, l, 13f, R.color.on_accent, 700).apply { alpha = 0.9f; setPadding(0, ctx.dp(3), 0, 0) })
                sheet.addView(hero, UI.matchWidth().apply { topMargin = ctx.dp(12) })
            }

            // Card.
            d.cardNumber?.let { n ->
                sheet.addView(UI.button(ctx, "Afficher ma carte (QR code)", R.drawable.ic_coin) { showCard(activity, d) }, UI.matchWidth().apply { topMargin = ctx.dp(12) })
            }

            // Bookings, rebuilt after each change.
            sheet.addView(UI.section(ctx, "Réservations"))
            val bookings = UI.column(ctx)
            sheet.addView(bookings, UI.matchWidth())
            fun fill(data: Canteen.Data, message: String?) {
                bookings.removeAllViews()
                message?.let { bookings.addView(UI.text(ctx, it, 13f, R.color.alert, 800).apply { setPadding(0, 0, 0, ctx.dp(8)) }) }
                val upcoming = data.days.filter { it.date >= LocalDate.now() }
                if (!data.canBook) bookings.addView(UI.text(ctx, "Ton établissement ne permet pas de réserver en ligne.", 14f, R.color.text2))
                else if (upcoming.isEmpty()) bookings.addView(UI.text(ctx, "Pas de jour à réserver pour l'instant.", 14f, R.color.text2))
                else for ((week, days) in upcoming.groupBy { it.booking.week }) {
                    val card = UI.card(ctx, padding = 12)
                    days.forEachIndexed { i, day ->
                        if (i > 0) card.addView(UI.divider(ctx))
                        val label = "${dayName(day.date, LocalDate.now())} ${if (day.date == LocalDate.now() || day.date == LocalDate.now().plusDays(1)) "${day.date.dayOfMonth} ${day.date.month.getDisplayName(TextStyle.SHORT, fr)}" else ""}".trim()
                        if (day.booking.allowed) {
                            card.addView(UI.check(ctx, "$label · ${day.booking.terminal}", { day.booking.booked > 0 }) { want ->
                                activity.worker.execute {
                                    val error = canteen.book(day, want)
                                    activity.runOnUiThread {
                                        if (error != null) Motion.failure(bookings)
                                        canteen.data()?.let { fill(it, error) }
                                        activity.refreshScreen()
                                    }
                                }
                            }, UI.matchWidth())
                        } else {
                            card.addView(UI.row(ctx).apply {
                                setPadding(0, ctx.dp(10), 0, ctx.dp(10))
                                addView(UI.text(ctx, label, 15f, R.color.text3), UI.weight())
                                addView(UI.text(ctx, day.booking.message ?: if (day.booking.booked > 0) "réservé" else "fermé", 13f, R.color.text3))
                            }, UI.matchWidth())
                        }
                    }
                    bookings.addView(card, UI.matchWidth().apply { bottomMargin = ctx.dp(10) })
                }
            }
            fill(d, null)

            // History.
            if (d.history.isNotEmpty()) {
                sheet.addView(UI.section(ctx, "Derniers passages et paiements"))
                val card = UI.card(ctx, padding = 12)
                d.history.take(10).forEachIndexed { i, h ->
                    if (i > 0) card.addView(UI.divider(ctx))
                    card.addView(UI.row(ctx).apply {
                        setPadding(0, ctx.dp(9), 0, ctx.dp(9))
                        val col = UI.column(ctx)
                        col.addView(UI.oneLine(UI.text(ctx, h.detail.ifEmpty { "Opération" }, 14f, weight = 800)))
                        col.addView(UI.text(ctx, h.date.take(10).split("-").reversed().joinToString("/"), 12f, R.color.text2))
                        addView(col, UI.weight())
                        addView(UI.title(ctx, (if (h.amount > 0) "+" else "") + Canteen.money(h.amount), 15f, if (h.amount > 0) R.color.success else R.color.text))
                    }, UI.matchWidth())
                }
                sheet.addView(card, UI.matchWidth())
            }

            sheet.addView(UI.ghostButton(ctx, "Recharger sur Turboself") {
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ESPACE))) }
            }, UI.matchWidth().apply { topMargin = ctx.dp(16) })
        }
    }

    // ---- Login -----------------------------------------------------------------------

    fun showLogin(activity: MainActivity, onDone: () -> Unit) {
        val ctx = activity
        Sheet.open(activity, activity.bottomInsetPx) { sheet, dialog ->
            Sheet.title(sheet, "Connecter Turboself", "La cantine dans Sallio : ta carte, ton solde, tes réservations")
            sheet.addView(UI.text(ctx, "Turboself ne donne pas de connexion durable : pour se mettre à jour tout seul, Sallio garde ton mot de passe Turboself chiffré sur ce téléphone. Il n'est envoyé qu'à Turboself, et « Déconnecter Turboself » (Réglages, Cantine) l'efface.", 13f, R.color.text2).apply {
                setPadding(0, ctx.dp(8), 0, ctx.dp(14))
            }, UI.matchWidth())
            fun field(hint: String, password: Boolean) = EditText(ctx).apply {
                this.hint = hint
                setSingleLine()
                inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                background = UI.rounded(ctx.getColor(R.color.surface2), ctx.dp(14).toFloat(), ctx.dp(2), ctx.getColor(R.color.edge))
                setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
                textSize = 15f
                typeface = UI.body(ctx, 700)
                setTextColor(ctx.getColor(R.color.text))
                setHintTextColor(ctx.getColor(R.color.text3))
            }
            val user = field("E-mail ou identifiant Turboself", false)
            val pass = field("Mot de passe", true).apply { imeOptions = EditorInfo.IME_ACTION_GO }
            sheet.addView(user, UI.matchWidth())
            sheet.addView(pass, UI.matchWidth().apply { topMargin = ctx.dp(10) })
            val message = UI.text(ctx, "", 13f, R.color.alert, 800).apply { visibility = View.GONE; setPadding(0, ctx.dp(10), 0, 0) }
            sheet.addView(message, UI.matchWidth())
            var busy = false
            val go = go@{
                if (busy) return@go
                val u = user.text.toString()
                val p = pass.text.toString()
                if (u.isBlank() || p.isEmpty()) {
                    message.text = "Entre ton e-mail et ton mot de passe Turboself."
                    message.visibility = View.VISIBLE
                    return@go
                }
                busy = true
                message.setTextColor(ctx.getColor(R.color.text2))
                message.text = "Connexion à Turboself…"
                message.visibility = View.VISIBLE
                activity.worker.execute {
                    val error = Canteen.get(ctx).connect(u, p)
                    activity.runOnUiThread {
                        busy = false
                        if (error == null) {
                            Motion.success(sheet)
                            dialog.dismiss()
                            onDone()
                        } else {
                            Motion.failure(sheet)
                            message.setTextColor(ctx.getColor(R.color.alert))
                            message.text = error
                        }
                    }
                }
            }
            pass.setOnEditorActionListener { _, _, _ -> go(); true }
            sheet.addView(UI.button(ctx, "Se connecter") { go() }, UI.matchWidth().apply { topMargin = ctx.dp(14) })
        }
    }
}
