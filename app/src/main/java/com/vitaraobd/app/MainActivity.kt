package com.vitaraobd.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.vitaraobd.app.ui.DashboardScreen
import com.vitaraobd.app.ui.LogsScreen
import com.vitaraobd.app.ui.MapHost
import com.vitaraobd.app.ui.MapScreen
import com.vitaraobd.app.ui.Screen
import com.vitaraobd.app.ui.SettingsHost
import com.vitaraobd.app.ui.SettingsScreen
import com.vitaraobd.app.ui.Theme
import com.vitaraobd.app.ui.TripScreen

/** Tela única com quatro abas: Painel, Viagem, Logs e Config. */
class MainActivity : Activity(), SettingsHost, MapHost {

    private lateinit var content: FrameLayout
    private lateinit var screens: List<Screen>
    private val tabs = ArrayList<TextView>()
    private var current = 0
    private var pendingChoice: (() -> Unit)? = null
    private var pendingExport: Journey? = null
    private var askedLocation = false
    private var lastBackMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = FrameLayout(this)
        screens = listOf(DashboardScreen(this), MapScreen(this, this), TripScreen(this), LogsScreen(this), SettingsScreen(this, this))
        current = savedInstanceState?.getInt("tab") ?: 0
        Engine.start()                  // não faz nada se já estiver rodando; religa após um "salvar e sair"
        buildChrome()
        applyKeepScreenOn()
        show(current)
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
        }
    }

    /**
     * Monta a moldura conforme a orientação: abas embaixo em retrato; em paisagem viram uma
     * coluna estreita à esquerda, para não gastar a altura (que é o que falta com o telefone deitado).
     */
    private fun buildChrome() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        (content.parent as? ViewGroup)?.removeView(content)
        tabs.clear()

        val bar = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.CARD)
        }
        listOf("Painel", "Mapa", "Viagem", "Logs", "Config").forEachIndexed { i, title ->
            val tab = TextView(this).apply {
                text = title
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(Theme.dp(context, 6), Theme.dp(context, 14), Theme.dp(context, 6), Theme.dp(context, 14))
                setOnClickListener { show(i) }
            }
            tabs += tab
            bar.addView(
                tab,
                if (landscape) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }

        val root = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
            fitsSystemWindows = true
        }
        if (landscape) {
            root.addView(bar, LinearLayout.LayoutParams(Theme.dp(this, 76), ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        } else {
            root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(bar)
        }
        setContentView(root)
        highlightTab()
    }

    private fun highlightTab() {
        tabs.forEachIndexed { i, t ->
            t.setTextColor(if (i == current) Theme.ACCENT else Theme.MUTED)
            t.setTypeface(null, if (i == current) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    /**
     * Voltar: de qualquer aba, vai para o Painel. No Painel, dois toques seguidos salvam o que
     * estiver em andamento e encerram o app.
     */
    @Deprecated("substituído pelo OnBackInvokedCallback em versões novas; mantido pela simplicidade")
    override fun onBackPressed() {
        if (current != 0) {
            show(0)
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBackMs < BACK_EXIT_WINDOW_MS) {
            saveAndExit()
        } else {
            lastBackMs = now
            Toast.makeText(this, "Pressione voltar de novo para salvar e sair", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAndExit() {
        val journey = Journeys.active
        AppLog.i("APP", "saindo a pedido do usuário" + if (journey != null) " (viagem '${journey.name}' fica salva e continua ao reabrir)" else "")
        Journeys.flush()        // viagem em andamento: grava arquivos e resumo parcial
        Engine.stop()           // para a leitura e salva os acumuladores de viagem e totais
        Gps.stopReal()
        Toast.makeText(this, if (journey != null) "Viagem salva. Ela continua quando o app for aberto de novo." else "Dados salvos.", Toast.LENGTH_SHORT).show()
        finishAndRemoveTask()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", current)
    }

    private fun show(index: Int) {
        if (content.childCount > 0) screens[current].onHidden()
        current = index
        content.removeAllViews()
        content.addView(screens[index].view)
        highlightTab()
        screens[index].onShown()
    }

    override fun onStart() {
        super.onStart()
        Engine.listener = { screens[current].refresh() }
        Gps.startReal()                 // não faz nada se a permissão ainda não foi dada
        screens[current].onShown()
    }

    override fun onStop() {
        super.onStop()
        // A leitura continua em segundo plano enquanto o processo existir; só a tela para de redesenhar.
        Engine.listener = null
        screens[current].onHidden()
        // Com uma viagem sendo gravada o GPS fica ligado; sem viagem, economiza bateria.
        if (Journeys.active == null) Gps.stopReal()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildChrome()                   // reposiciona as abas (embaixo ou na lateral)
        screens[current].refresh()      // painel e viagem refazem o layout ao detectar a nova orientação
    }

    // ---------------------------------------------------------------- MapHost

    override fun requestLocationPermission() {
        if (Gps.hasPermission()) { Gps.startReal(); return }
        if (askedLocation) return           // não insiste a cada troca de aba
        askedLocation = true
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
    }

    override fun exportJourney(journey: Journey) {
        pendingExport = journey
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "viagem_${journey.id}.zip")
        }
        try {
            startActivityForResult(intent, REQ_EXPORT)
        } catch (e: Exception) {
            AppLog.e("APP", "não foi possível abrir o seletor de arquivos", e)
            Toast.makeText(this, "Sem seletor de arquivos. A viagem está em ${journey.dir.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT) return
        val journey = pendingExport
        pendingExport = null
        val uri = data?.data
        if (resultCode != RESULT_OK || journey == null || uri == null) return
        Thread {
            try {
                val out = contentResolver.openOutputStream(uri) ?: throw IllegalStateException("destino indisponível")
                val n = Journeys.zip(journey, out)
                AppLog.i("VIAGEM", "ZIP exportado: $n arquivos de '${journey.name}'")
                runOnUiThread { Toast.makeText(this, "ZIP salvo ($n arquivos)", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                AppLog.e("VIAGEM", "falha ao exportar o ZIP", e)
                runOnUiThread { Toast.makeText(this, "Falha ao exportar: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    // ---------------------------------------------------------------- SettingsHost

    override fun applyKeepScreenOn() {
        if (Engine.settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun chooseBluetoothDevice(onChosen: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingChoice = onChosen
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
            return
        }
        showDeviceDialog(onChosen)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            val ok = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            AppLog.i("APP", "permissão de localização: ${if (ok) "concedida" else "negada"}")
            if (ok) Gps.startReal()
            screens[current].refresh()
            return
        }
        if (requestCode != REQ_BT) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        AppLog.i("APP", "permissão de Bluetooth: ${if (granted) "concedida" else "negada"}")
        val next = pendingChoice
        pendingChoice = null
        if (granted && next != null) showDeviceDialog(next)
        else if (!granted) Toast.makeText(this, "Sem a permissão de dispositivos próximos não é possível conectar ao adaptador.", Toast.LENGTH_LONG).show()
    }

    @SuppressLint("MissingPermission")
    private fun showDeviceDialog(onChosen: () -> Unit) {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val devices = try { adapter?.bondedDevices?.toList().orEmpty() } catch (e: SecurityException) { emptyList() }
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Nenhum adaptador pareado")
                .setMessage("Pareie o ELM327 nas configurações de Bluetooth do Android (PIN 1234 ou 0000) e volte aqui.")
                .setPositiveButton("OK", null).show()
            return
        }
        val names = devices.map { "${it.name ?: "?"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Escolha o adaptador")
            .setItems(names) { _, i ->
                val d = devices[i]
                Engine.settings.btAddress = d.address
                Engine.settings.btName = d.name ?: ""
                AppLog.i("CONFIG", "adaptador escolhido: ${d.name} ${d.address}")
                onChosen()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    companion object {
        private const val BACK_EXIT_WINDOW_MS = 2500L
        private const val REQ_BT = 1
        private const val REQ_LOCATION = 2
        private const val REQ_EXPORT = 3
    }
}
