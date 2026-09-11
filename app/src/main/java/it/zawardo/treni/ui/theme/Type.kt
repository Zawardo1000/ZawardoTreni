package it.zawardo.treni.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import it.zawardo.treni.R

/*
 * Barlow, il carattere della segnaletica.
 *
 * Nasce dai cartelli stradali, e un orario scritto con lui si legge come su un
 * cartello di stazione. Il semicondensato porta orari e binari: le cifre stanno
 * in colonne strette e lasciano ai nomi delle stazioni lo spazio che, nel
 * dettaglio della corsa, le colonne degli orari altrimenti gli tolgono.
 *
 * Licenza SIL OFL 1.1, che permette di incorporarlo nell'app.
 */

val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
)

val BarlowSemiCondensed = FontFamily(
    Font(R.font.barlow_semi_condensed_regular, FontWeight.Normal),
    Font(R.font.barlow_semi_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_semi_condensed_semibold, FontWeight.SemiBold),
)

/**
 * Cifre tabulari: ogni cifra occupa la stessa larghezza, e 11:11 sta sopra
 * 08:08 senza scalini. Roboto le aveva tabulari di suo, Barlow va chiesto.
 * Controllato sui file del font: la feature c'e' in tutti.
 */
private const val TABULARI = "tnum"

private fun TextStyle.barlow() = copy(fontFamily = Barlow, fontFeatureSettings = TABULARI)

private val base = Typography()

/** La scala di Material per intero, in Barlow e con le cifre tabulari. */
val TreniTypography = Typography(
    displayLarge = base.displayLarge.barlow(),
    displayMedium = base.displayMedium.barlow(),
    displaySmall = base.displaySmall.barlow(),
    headlineLarge = base.headlineLarge.barlow(),
    headlineMedium = base.headlineMedium.barlow(),
    headlineSmall = base.headlineSmall.barlow(),
    titleLarge = base.titleLarge.barlow(),
    titleMedium = base.titleMedium.barlow(),
    titleSmall = base.titleSmall.barlow(),
    bodyLarge = base.bodyLarge.barlow(),
    bodyMedium = base.bodyMedium.barlow(),
    bodySmall = base.bodySmall.barlow(),
    labelLarge = base.labelLarge.barlow(),
    labelMedium = base.labelMedium.barlow(),
    labelSmall = base.labelSmall.barlow(),
)

/**
 * Orari e binari, fuori dalla scala di Material.
 *
 * Sono i dati per cui si apre l'app, e hanno bisogno di una cosa che la scala
 * non da': stare in colonna. Semicondensati e tabulari, con una grandezza per
 * ciascun posto in cui compaiono, cosi' lo stesso orario non esce di tre corpi
 * diversi in tre schermate.
 */
object Cifre {

    /** Partenza e arrivo nell'elenco delle soluzioni. */
    val grandi = TextStyle(
        fontFamily = BarlowSemiCondensed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        fontFeatureSettings = TABULARI,
    )

    /** Lo scarto in cima alla corsa, sempre visibile. */
    val scarto = TextStyle(
        fontFamily = BarlowSemiCondensed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        fontFeatureSettings = TABULARI,
    )

    /** Il numero dentro la pillola del binario. */
    val binario = TextStyle(
        fontFamily = BarlowSemiCondensed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        fontFeatureSettings = TABULARI,
    )

    /**
     * Le colonne del dettaglio corsa, e l'orario reale sotto quello
     * dell'elenco. Arrivo e partenza hanno lo stesso stile: con due corpi
     * diversi la colonna smette di sembrare una colonna.
     */
    val riga = TextStyle(
        fontFamily = BarlowSemiCondensed,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 21.sp,
        fontFeatureSettings = TABULARI,
    )
}
