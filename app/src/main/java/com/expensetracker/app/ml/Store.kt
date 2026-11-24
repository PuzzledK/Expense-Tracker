package com.expensetracker.app.ml

import com.expensetracker.app.data.Category
import com.expensetracker.app.data.CategoryRegistry

/**
 * Tab-separated learnt memory, one record per line:
 * `C  noteKey  note  categoryId  ts  n` — a correction;
 * `P  payeeKey  noteKey  note  categoryId  weight  ts  hourSum  hourN  logAmtSum  amtN` — payee → note.
 */
internal object Store {
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    private fun cat(id: String): Category =
        if (CategoryRegistry.exists(id)) Category.fromId(id) else Category.create(id)   // custom ids not loaded yet

    fun encode(corrections: Map<String, Correction>, payees: Map<String, Map<String, Predictor.PayeeNote>>): String = buildString {
        for ((k, c) in corrections) {
            append("C\t").append(clean(k)).append('\t').append(clean(c.note)).append('\t').append(c.cat.id)
                .append('\t').append(c.ts).append('\t').append(c.n).append('\n')
        }
        for ((pk, notes) in payees) for ((nk, p) in notes) {
            append(listOf("P", clean(pk), clean(nk), clean(p.note), p.cat.id, p.weight, p.ts, p.hourSum, p.hourN, p.logAmtSum, p.amtN).joinToString("\t")).append('\n')
        }
    }

    fun decode(text: String, corrections: MutableMap<String, Correction>, payees: MutableMap<String, HashMap<String, Predictor.PayeeNote>>) {
        for (line in text.lineSequence()) {
            val p = line.split('\t')
            runCatching {
                when {
                    p[0] == "C" && p.size >= 6 -> corrections[p[1]] = Correction(p[2], cat(p[3]), p[4].toLong(), p[5].toInt())
                    p[0] == "P" && p.size >= 11 -> payees.getOrPut(p[1]) { HashMap() }[p[2]] = Predictor.PayeeNote(
                        p[3], cat(p[4]), p[5].toDouble(), p[6].toLong(), p[7].toDouble(), p[8].toInt(), p[9].toDouble(), p[10].toInt(),
                    )
                }
            }
        }
    }
}
