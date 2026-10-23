package com.ydeclik.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sauvegarde / restauration de toutes les données de l'application dans un seul fichier JSON :
 * réglages du métronome, presets, setlists, statistiques de BPM, progression, routines, utilisation
 * et emplacement des fichiers (avec leurs noms, pour pouvoir les relier sur un autre téléphone).
 */
object Backup {
    private val FILES = listOf("ydeclik", "ydeclik_ex", "ydeclik_sl")

    fun export(ctx: Context, names: Map<String, String>): String {
        val root = JSONObject().put("app", "ydeclik").put("version", 1).put("date", System.currentTimeMillis())
        val all = JSONObject()
        for (f in FILES) {
            val sp = ctx.getSharedPreferences(f, Context.MODE_PRIVATE)
            val o = JSONObject()
            for ((k, v) in sp.all) {
                val e = JSONObject()
                when (v) {
                    is String -> e.put("t", "s").put("v", v)
                    is Int -> e.put("t", "i").put("v", v)
                    is Long -> e.put("t", "l").put("v", v)
                    is Boolean -> e.put("t", "b").put("v", v)
                    is Float -> e.put("t", "f").put("v", v.toDouble())
                    is Set<*> -> e.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                    else -> continue
                }
                o.put(k, e)
            }
            if (f == "ydeclik_ex") {
                // Noms des fichiers : permettent de les retrouver sur un autre téléphone.
                val n = JSONObject()
                names.forEach { (uri, name) -> n.put(uri, name) }
                o.put("oldNames", JSONObject().put("t", "s").put("v", n.toString()))
            }
            all.put(f, o)
        }
        root.put("prefs", all)
        return root.toString(1)
    }

    fun isValid(json: String): Boolean = runCatching {
        val r = JSONObject(json)
        r.optString("app") == "ydeclik" && r.getJSONObject("prefs").length() > 0
    }.getOrDefault(false)

    /** Remplace les données actuelles. L'application doit être relancée ensuite. */
    fun restore(ctx: Context, json: String): Boolean = runCatching {
        val all = JSONObject(json).getJSONObject("prefs")
        for (f in FILES) {
            val o = all.optJSONObject(f) ?: continue
            val ed = ctx.getSharedPreferences(f, Context.MODE_PRIVATE).edit().clear()
            o.keys().forEach { k ->
                val e = o.getJSONObject(k)
                when (e.getString("t")) {
                    "s" -> ed.putString(k, e.getString("v"))
                    "i" -> ed.putInt(k, e.getInt("v"))
                    "l" -> ed.putLong(k, e.getLong("v"))
                    "b" -> ed.putBoolean(k, e.getBoolean("v"))
                    "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                    "ss" -> {
                        val a = e.getJSONArray("v")
                        ed.putStringSet(k, HashSet<String>(List(a.length()) { a.getString(it) }))
                    }
                }
            }
            ed.commit()
        }
        true
    }.getOrDefault(false)
}
