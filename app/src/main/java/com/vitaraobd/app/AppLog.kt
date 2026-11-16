package com.vitaraobd.app

import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log do app: guarda as últimas linhas em memória (para a tela de Logs em tempo real)
 * e grava tudo em arquivo (um por dia) para análise posterior.
 */
object AppLog {

    enum class Level(val tag: String) { DEBUG("D"), INFO("I"), WARN("W"), ERROR("E") }

    class Entry(val time: Long, val level: Level, val tag: String, val msg: String) {
        fun line(): String = "${TIME.format(Date(time))} ${level.tag}/$tag: $msg"
    }

    private const val MAX_ENTRIES = 1500
    private val TIME = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val buffer = ArrayDeque<Entry>()
    private var file: File? = null

    /** Muda a cada nova linha; a tela de Logs usa para saber se precisa redesenhar. */
    @Volatile
    var revision: Long = 0
        private set

    fun init(dir: File) {
        dir.mkdirs()
        // Mantém só os 7 arquivos mais recentes.
        dir.listFiles()?.sortedByDescending { it.name }?.drop(7)?.forEach { it.delete() }
        file = File(dir, "app_${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.log")
    }

    fun logFile(): File? = file

    fun d(tag: String, msg: String) = add(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = add(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = add(Level.WARN, tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        add(Level.ERROR, tag, if (t == null) msg else "$msg :: ${t.javaClass.simpleName}: ${t.message}")

    @Synchronized
    private fun add(level: Level, tag: String, msg: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, msg)
        buffer.addLast(entry)
        while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        revision++
        val f = file ?: return
        try {
            FileWriter(f, true).use { it.write(SimpleDateFormat("yyyy-MM-dd ", Locale.US).format(Date(entry.time)) + entry.line() + "\n") }
        } catch (_: Exception) {
            // Sem armazenamento: o log em memória continua funcionando.
        }
    }

    @Synchronized
    fun snapshot(minLevel: Level): List<Entry> = buffer.filter { it.level >= minLevel }

    @Synchronized
    fun clear() {
        buffer.clear()
        revision++
    }
}
