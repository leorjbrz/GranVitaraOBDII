package com.vitaraobd.app.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.vitaraobd.app.AppLog
import com.vitaraobd.app.Engine
import com.vitaraobd.app.Gps
import com.vitaraobd.app.Journey
import com.vitaraobd.app.Journeys
import com.vitaraobd.app.Router
import com.vitaraobd.app.Sensor
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** O que a tela do mapa precisa pedir à Activity. */
interface MapHost {
    fun requestLocationPermission()
    /** Abre o seletor de arquivos e grava o ZIP da viagem no local escolhido. */
    fun exportJourney(journey: Journey)
}

/** Pequena rosa dos ventos: a marca vermelha aponta para o norte. */
class CompassView(ctx: Context) : View(ctx) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Theme.MUTED }
    private val north = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.DANGER }
    private val south = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TEXT }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.DANGER; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val heading = Gps.heading
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) * 0.9f
        ring.strokeWidth = r * 0.07f
        c.drawCircle(cx, cy, r, ring)
        if (heading.isNaN()) return
        c.save()
        c.rotate(-heading, cx, cy)          // gira o mostrador para o norte ficar no lugar certo
        path.reset(); path.moveTo(cx, cy - r * 0.62f); path.lineTo(cx - r * 0.2f, cy); path.lineTo(cx + r * 0.2f, cy); path.close()
        c.drawPath(path, north)
        path.reset(); path.moveTo(cx, cy + r * 0.62f); path.lineTo(cx - r * 0.2f, cy); path.lineTo(cx + r * 0.2f, cy); path.close()
        c.drawPath(path, south)
        text.textSize = r * 0.34f
        c.drawText("N", cx, cy - r * 0.66f, text)
        c.restore()
    }
}

class MapScreen(private val ctx: Context, private val host: MapHost) : Screen {

    private val root = FrameLayout(ctx)
    override val view: View get() = root

    private val map: MapView
    private val compass = CompassView(ctx)
    private val info = ctx.label("", 12.5f)
    /** Crédito dos dados do mapa, exigido pelos provedores. */
    private val attribution = ctx.label("", 9f).apply {
        setBackgroundColor(Color.argb(170, 0x0D, 0x11, 0x17))
        setPadding(Theme.dp(ctx, 6), Theme.dp(ctx, 1), Theme.dp(ctx, 6), Theme.dp(ctx, 1))
    }
    private val widgetArea = FrameLayout(ctx)
    private val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    private val me: Marker
    private val originMarker: Marker
    private val destMarker: Marker
    private val trackLine: Polyline
    private val destLine: Polyline
    private val routeLine: Polyline
    private val rotationGesture: RotationGestureOverlay
    private val sideButtons = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var routeRevision = -1
    private var routedDest: Pair<Double, Double>? = null

    private var follow = true
    private var centeredOnce = false
    private var drawnPoints = 0
    private var journeyRevision = -1
    private var settingsRevision = -1
    private var buttonsState = ""
    private val widgets = ArrayList<TileView>()

    // Declarada antes do bloco init porque ele já usa a lista (applyLayer).
    private val layers: List<Pair<String, ITileSource>> = listOf(
        "Mapa de ruas (OpenStreetMap)" to TileSourceFactory.MAPNIK,
        "Terreno / relevo (OpenTopoMap)" to TileSourceFactory.OpenTopo,
        "Satélite (Esri World Imagery)" to object : OnlineTileSourceBase(
            "EsriWorldImagery", 0, 19, 256, "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
            "Imagens © Esri e fornecedores",
        ) {
            override fun getTileURLString(i: Long): String =
                baseUrl + MapTileIndex.getZoom(i) + "/" + MapTileIndex.getY(i) + "/" + MapTileIndex.getX(i)
        },
    )

    private val layerCredits = listOf(
        "© colaboradores do OpenStreetMap",
        "Mapa: © colaboradores do OpenStreetMap, SRTM · Estilo: © OpenTopoMap (CC-BY-SA)",
        "Imagens © Esri, Maxar, Earthstar Geographics",
    )

    init {
        // O osmdroid guarda os blocos do mapa em disco; usa a pasta do próprio app (sem permissão de armazenamento).
        Configuration.getInstance().apply {
            userAgentValue = ctx.packageName
            osmdroidBasePath = File(ctx.filesDir, "osmdroid")
            osmdroidTileCache = File(ctx.cacheDir, "osmdroid_tiles")
        }
        map = MapView(ctx).apply {
            setMultiTouchControls(true)          // pinça com dois dedos aproxima e afasta
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            // O mapa sai e volta da tela ao trocar de aba ou girar o aparelho; sem isto o osmdroid
            // destrói marcadores e linhas ao ser desanexado e o app trava no próximo desenho.
            setDestroyMode(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(-22.9068, -43.1729))
            isTilesScaledToDpi = true
        }
        applyLayer()

        trackLine = Polyline(map).apply { outlinePaint.color = Theme.ACCENT; outlinePaint.strokeWidth = Theme.dp(ctx, 4f); infoWindow = null }
        destLine = Polyline(map).apply {
            outlinePaint.color = Color.argb(200, 0xFF, 0x4D, 0x4D)
            outlinePaint.strokeWidth = Theme.dp(ctx, 2f)
            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(Theme.dp(ctx, 8f), Theme.dp(ctx, 6f)), 0f)
            infoWindow = null
        }
        routeLine = Polyline(map).apply {
            outlinePaint.color = Color.argb(215, 0x8E, 0x5B, 0xFF)
            outlinePaint.strokeWidth = Theme.dp(ctx, 6f)
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            infoWindow = null
        }
        rotationGesture = RotationGestureOverlay(map).apply { isEnabled = Engine.settings.mapOrientation == 1 }
        originMarker = marker(dot(Theme.OK, "A"), Marker.ANCHOR_CENTER)
        destMarker = marker(dot(Theme.DANGER, "B"), Marker.ANCHOR_CENTER)
        me = marker(arrow(), Marker.ANCHOR_CENTER).apply { isFlat = true }

        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p == null) return false
                val journey = Journeys.active
                val options = ArrayList<Pair<String, () -> Unit>>()
                if (journey != null) {
                    options += "Definir como destino da viagem (e calcular a rota)" to {
                        Journeys.setDestination(p.latitude, p.longitude)
                        routeTo(p.latitude, p.longitude)
                    }
                }
                options += "Calcular rota até este ponto" to { routeTo(p.latitude, p.longitude) }
                AlertDialog.Builder(ctx).setTitle("Ponto no mapa")
                    .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second(); update() }
                    .setNegativeButton("Cancelar", null).show()
                return true
            }
        }))
        map.overlays.add(rotationGesture)
        map.overlays.add(routeLine)
        map.overlays.add(destLine)
        map.overlays.add(trackLine)
        // Tocar na bússola alterna a orientação: norte para cima → giro livre → sempre para a frente.
        compass.setOnClickListener { setOrientationMode((Engine.settings.mapOrientation + 1) % 3) }

        // Arrastar o mapa desliga o "seguir posição"; o botão Centralizar liga de novo.
        @SuppressLint("ClickableViewAccessibility")
        map.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_MOVE) follow = false; false }

        root.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))

        val pad = Theme.dp(ctx, 8)
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(Color.argb(225, 0x18, 0x1E, 0x27)); cornerRadius = Theme.dp(ctx, 10f) }
            setPadding(pad, pad, pad, pad)
        }
        card.addView(compass, LinearLayout.LayoutParams(Theme.dp(ctx, 54), Theme.dp(ctx, 54)).apply { rightMargin = pad })
        card.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))
        column.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(pad, pad, pad, 0) })
        column.addView(widgetArea, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { setMargins(pad, pad, pad, pad) })
        column.addView(attribution, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = pad / 2 })
        buttons.setPadding(pad, 0, pad, pad)
        column.addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ---------------------------------------------------------------- desenho dos marcadores

    private fun marker(icon: Drawable, anchorV: Float): Marker = Marker(map).apply {
        this.icon = icon
        setAnchor(Marker.ANCHOR_CENTER, anchorV)
        setInfoWindow(null)
    }

    private fun dot(color: Int, letter: String): Drawable {
        val s = Theme.dp(ctx, 30)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE; c.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.color = color; c.drawCircle(s / 2f, s / 2f, s / 2f - Theme.dp(ctx, 2.5f), p)
        p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = s * 0.5f
        c.drawText(letter, s / 2f, s / 2f + p.textSize * 0.36f, p)
        return BitmapDrawable(ctx.resources, bmp)
    }

    /** Seta apontando para cima (norte); o marcador é girado conforme a direção. */
    private fun arrow(): Drawable {
        val s = Theme.dp(ctx, 38)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val path = Path().apply {
            moveTo(s / 2f, s * 0.06f); lineTo(s * 0.86f, s * 0.92f); lineTo(s / 2f, s * 0.72f); lineTo(s * 0.14f, s * 0.92f); close()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Theme.ACCENT; c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = Theme.dp(ctx, 2.5f); p.color = Color.WHITE; p.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, p)
        return BitmapDrawable(ctx.resources, bmp)
    }

    // ---------------------------------------------------------------- camadas


    private fun applyLayer() {
        val index = Engine.settings.mapLayer.coerceIn(0, layers.size - 1)
        map.setTileSource(layers[index].second)
        attribution.text = layerCredits[index]
    }

    private fun showMapOptions() {
        val st = Engine.settings
        val names = layers.mapIndexed { i, l -> (if (i == st.mapLayer) "● " else "○ ") + l.first } +
            listOf("Mini-gráficos sobre o mapa…", "Viagens salvas…")
        AlertDialog.Builder(ctx).setTitle("Mapa")
            .setItems(names.toTypedArray()) { _, i ->
                if (i < layers.size) {
                    st.mapLayer = i
                    applyLayer()
                    map.invalidate()
                    AppLog.i("MAPA", "camada: ${layers[i].first}")
                } else if (i == layers.size) showWidgetOptions() else showSaved()
            }.show()
    }

    // ---------------------------------------------------------------- mini-gráficos sobre o mapa

    private val corners = listOf("Superior esquerdo", "Superior direito", "Inferior esquerdo", "Inferior direito")

    private fun showWidgetOptions() {
        val st = Engine.settings
        val sensors = Sensor.entries.filter { !it.experimental || st.experimental }
        val sensorNames = listOf("(nenhum)") + sensors.map { it.title }
        val pad = Theme.dp(ctx, 16)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        for (n in 1..2) {
            box.addView(ctx.label("Mini-gráfico $n", 12f, Theme.MUTED).apply { setPadding(0, pad / 2, 0, 0) })
            val current = st.mapWidget(n)
            box.addView(ctx.spinner(sensorNames, if (current == null) 0 else sensors.indexOf(current) + 1) { i ->
                st.setMapWidget(n, if (i == 0) null else sensors[i - 1]); refresh()
            })
            box.addView(ctx.spinner(corners, st.mapWidgetCorner(n)) { i -> st.setMapWidgetCorner(n, i); refresh() })
        }
        val alphaLabel = ctx.label("Opacidade: ${st.mapWidgetAlpha} %", 12f, Theme.MUTED).apply { setPadding(0, pad, 0, 0) }
        box.addView(alphaLabel)
        box.addView(SeekBar(ctx).apply {
            max = 80                                    // 20 % a 100 %
            progress = st.mapWidgetAlpha - 20
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    st.mapWidgetAlpha = p + 20
                    alphaLabel.text = "Opacidade: ${p + 20} %"
                    refresh()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
        box.addView(ctx.label("As mudanças aparecem na hora, atrás desta janela.", 11f, Theme.MUTED).apply { setPadding(0, pad / 2, 0, 0) })
        AlertDialog.Builder(ctx).setTitle("Mini-gráficos sobre o mapa").setView(box).setPositiveButton("Fechar", null).show()
    }

    private fun rebuildWidgets() {
        val st = Engine.settings
        settingsRevision = st.revision
        widgetArea.removeAllViews()
        widgets.clear()
        buildSideButtons()
        widgetArea.addView(sideButtons, FrameLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL })
        val w = Theme.dp(ctx, 148)
        val h = Theme.dp(ctx, 92)
        val gap = Theme.dp(ctx, 8)
        val used = HashMap<Int, Int>()          // canto → quantos já estão nele (para empilhar)
        for (n in 1..2) {
            val sensor = st.mapWidget(n) ?: continue
            if (sensor.experimental && !st.experimental) continue
            val corner = st.mapWidgetCorner(n).coerceIn(0, 3)
            val stack = used.getOrDefault(corner, 0)
            used[corner] = stack + 1
            val tile = TileView(ctx, sensor).apply { alpha = st.mapWidgetAlpha / 100f }
            val lp = FrameLayout.LayoutParams(w, h)
            lp.gravity = (if (corner < 2) Gravity.TOP else Gravity.BOTTOM) or (if (corner % 2 == 0) Gravity.START else Gravity.END)
            val offset = stack * (h + gap)
            if (corner < 2) lp.topMargin = offset else lp.bottomMargin = offset
            widgetArea.addView(tile, lp)
            widgets += tile
        }
    }

    // ---------------------------------------------------------------- botões

    private fun rebuildButtons() {
        val j = Journeys.active
        val state = when { j == null -> "idle"; j.paused -> "paused"; else -> "active" }
        if (state == buttonsState) return
        buttonsState = state
        buttons.removeAllViews()
        val gap = Theme.dp(ctx, 6)
        fun add(text: String, weight: Float = 1f, onClick: () -> Unit) {
            buttons.addView(ctx.flatButton(text, onClick).apply { textSize = 12f; setPadding(gap / 2, 0, gap / 2, 0) },
                LinearLayout.LayoutParams(0, WRAP, weight).apply { leftMargin = gap / 2; rightMargin = gap / 2 })
        }
        add("Mapa") { showMapOptions() }
        add("Centralizar", 1.25f) { follow = true; centeredOnce = false; update() }
        when (state) {
            "idle" -> {
                add("Iniciar viagem", 1.5f) { showStartDialog() }
                add("Rota") { showRouteMenu() }
            }
            "active" -> {
                add("Pausar") { showPauseDialog() }
                add("Finalizar", 1.15f) { confirmFinish() }
                add("Rota") { showRouteMenu() }
            }
            else -> {
                add("Retomar", 1.2f) { Journeys.resume(); update() }
                add("Finalizar", 1.15f) { confirmFinish() }
                add("Rota") { showRouteMenu() }
            }
        }
    }

    private fun field(hint: String, number: Boolean = false): EditText = EditText(ctx).apply {
        this.hint = hint
        textSize = 15f
        setSingleLine()
        if (number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun showStartDialog() {
        val pad = Theme.dp(ctx, 16)
        val name = field("Nome da viagem (ex.: RJ → SP)")
        val origin = field("Origem (vazio = posição atual)")
        val dest = field("Destino (ex.: São Paulo, SP)")
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        box.addView(name); box.addView(origin); box.addView(dest)
        box.addView(ctx.label(
            "A partir do início são gravados todos os dados do carro e o trajeto do GPS. " +
                "Se o destino não for localizado pelo nome, toque e segure no mapa para marcá-lo.",
            12f, Theme.MUTED,
        ).apply { setPadding(0, pad / 2, 0, 0) })
        AlertDialog.Builder(ctx).setTitle("Nova viagem").setView(box)
            .setPositiveButton("Iniciar") { _, _ ->
                val o = origin.text.toString().trim()
                val d = dest.text.toString().trim()
                val n = name.text.toString().trim().ifEmpty { listOf(o.ifEmpty { "Origem" }, d.ifEmpty { "Destino" }).joinToString(" → ") }
                Journeys.start(n, o, d)
                follow = true
                update()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun showPauseDialog() {
        val pad = Theme.dp(ctx, 16)
        var reason = 0
        val note = field("Observação (opcional)")
        val liters = field("Litros abastecidos (opcional)", true)
        val cost = field("Valor pago (opcional)", true)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        box.addView(ctx.label("Motivo", 12f, Theme.MUTED))
        box.addView(ctx.spinner(Journeys.PAUSE_REASONS, 0) { i ->
            reason = i
            val fuel = i == 0
            liters.visibility = if (fuel) View.VISIBLE else View.GONE
            cost.visibility = if (fuel) View.VISIBLE else View.GONE
        })
        box.addView(liters); box.addView(cost); box.addView(note)
        box.addView(ctx.label("Durante a pausa o tempo e a distância não contam para a viagem; os dados continuam sendo gravados, marcados como pausa.", 12f, Theme.MUTED).apply {
            setPadding(0, pad / 2, 0, 0)
        })
        AlertDialog.Builder(ctx).setTitle("Pausar viagem").setView(box)
            .setPositiveButton("Pausar") { _, _ ->
                fun num(e: EditText) = e.text.toString().replace(',', '.').toDoubleOrNull()
                val fuel = reason == 0
                Journeys.pause(Journeys.PAUSE_REASONS[reason], note.text.toString().trim(), if (fuel) num(liters) else null, if (fuel) num(cost) else null)
                update()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun confirmFinish() {
        val j = Journeys.active ?: return
        AlertDialog.Builder(ctx).setTitle("Finalizar viagem?")
            .setMessage("'${j.name}' será encerrada e salva. Depois você pode baixar o ZIP com todos os dados.")
            .setPositiveButton("Finalizar") { _, _ ->
                Journeys.finish()
                update()
                showJourney(j)
            }
            .setNegativeButton("Continuar viagem", null).show()
    }

    private fun showJourney(j: Journey) {
        val text = TextView(ctx).apply {
            this.text = Journeys.summaryText(j)
            textSize = 13f
            setTextIsSelectable(true)
            val p = Theme.dp(ctx, 16)
            setPadding(p, p / 2, p, 0)
        }
        AlertDialog.Builder(ctx).setTitle("Viagem salva")
            .setView(android.widget.ScrollView(ctx).apply { addView(text) })
            .setPositiveButton("Baixar ZIP") { _, _ -> host.exportJourney(j) }
            .setNeutralButton("Excluir") { _, _ ->
                AlertDialog.Builder(ctx).setMessage("Excluir '${j.name}' e todos os seus arquivos?")
                    .setPositiveButton("Excluir") { _, _ -> Journeys.delete(j) }
                    .setNegativeButton("Cancelar", null).show()
            }
            .setNegativeButton("Fechar", null).show()
    }

    private fun showSaved() {
        val list = Journeys.saved()
        if (list.isEmpty()) {
            AlertDialog.Builder(ctx).setTitle("Viagens salvas").setMessage("Nenhuma viagem salva ainda.").setPositiveButton("OK", null).show()
            return
        }
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)
        val names = list.map { "${it.name}\n${fmt.format(Date(it.startMs))} · ${String.format(Locale("pt", "BR"), "%.1f", it.distKm)} km · ${Journeys.hms(it.movingS)}" }
        AlertDialog.Builder(ctx).setTitle("Viagens salvas")
            .setItems(names.toTypedArray()) { _, i -> showJourney(list[i]) }
            .setNegativeButton("Fechar", null).show()
    }

    // ---------------------------------------------------------------- rota

    private fun routeTo(lat: Double, lon: Double) {
        val f = Gps.fix
        if (f == null) {
            AlertDialog.Builder(ctx).setMessage("Ainda não há posição do GPS para servir de ponto de partida.").setPositiveButton("OK", null).show()
            return
        }
        Router.request(f.lat, f.lon, lat, lon)
    }

    private fun showRouteMenu() {
        val d = Journeys.active?.dest
        val route = Router.route
        val options = ArrayList<Pair<String, () -> Unit>>()
        if (d?.lat != null && d.lon != null) options += "Calcular rota até o destino da viagem" to { routeTo(d.lat, d.lon) }
        if (route != null) {
            options += "Recalcular a partir daqui" to { routeTo(route.destLat, route.destLon) }
            options += "Ver os passos da rota" to { showRouteSteps() }
            options += "Remover a rota do mapa" to { Router.clear() }
        }
        val target = if (route != null) Triple(route.destLat, route.destLon, d?.name.orEmpty()) else if (d?.lat != null && d.lon != null) Triple(d.lat, d.lon, d.name) else null
        if (target != null) options += "Abrir em outro app de navegação" to { openExternalRoute(target.first, target.second, target.third) }
        if (options.isEmpty()) {
            AlertDialog.Builder(ctx).setTitle("Rota")
                .setMessage("Toque e segure em um ponto do mapa para calcular uma rota até ele, ou inicie uma viagem com destino.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(ctx).setTitle("Rota")
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second(); update() }
            .setNegativeButton("Fechar", null).show()
    }

    private fun showRouteSteps() {
        val r = Router.route ?: return
        val br = Locale("pt", "BR")
        val lines = r.steps.mapIndexed { i, s ->
            val dist = if (s.lengthM >= 1000) String.format(br, "%.1f km", s.lengthM / 1000) else String.format(br, "%.0f m", s.lengthM)
            "${i + 1}. ${s.text}" + if (i < r.steps.size - 1) "  —  $dist" else ""
        }
        val text = TextView(ctx).apply {
            this.text = String.format(br, "%.1f km · %s\n\n", r.distanceM / 1000, Journeys.hms(r.durationS)) + lines.joinToString("\n\n")
            textSize = 13f
            setTextIsSelectable(true)
            val p = Theme.dp(ctx, 16)
            setPadding(p, p / 2, p, 0)
        }
        AlertDialog.Builder(ctx).setTitle("Passos da rota")
            .setView(android.widget.ScrollView(ctx).apply { addView(text) })
            .setPositiveButton("Fechar", null).show()
    }

    /** Entrega o destino a um app de navegação instalado (Google Maps, Waze…). */
    private fun openExternalRoute(lat: Double, lon: Double, name: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(name.ifBlank { "Destino" })})")))
        } catch (e: Exception) {
            AppLog.w("MAPA", "nenhum app de navegação disponível: ${e.message}")
            AlertDialog.Builder(ctx).setMessage("Nenhum app de navegação instalado.").setPositiveButton("OK", null).show()
        }
    }

    // ---------------------------------------------------------------- orientação e zoom

    private val orientationLabels = listOf("norte para cima", "giro livre", "sempre para a frente")

    private fun setOrientationMode(mode: Int) {
        Engine.settings.mapOrientation = mode
        rotationGesture.isEnabled = mode == 1
        if (mode == 0) map.mapOrientation = 0f
        if (mode == 2) follow = true
        AppLog.i("MAPA", "orientação: " + orientationLabels[mode])
        android.widget.Toast.makeText(ctx, "Mapa: " + orientationLabels[mode], android.widget.Toast.LENGTH_SHORT).show()
        update()
    }

    /** No modo "frente", gira o mapa aos poucos até a direção do carro ficar para cima. */
    private fun applyHeadingUp() {
        val h = Gps.heading
        if (Engine.settings.mapOrientation != 2 || h.isNaN()) return
        val target = -h
        var diff = (target - map.mapOrientation) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        map.mapOrientation = map.mapOrientation + diff * 0.35f
    }

    private fun buildSideButtons() {
        sideButtons.removeAllViews()
        val size = Theme.dp(ctx, 46)
        val gap = Theme.dp(ctx, 6)
        fun add(b: android.widget.Button, width: Int = size) {
            b.setPadding(0, 0, 0, 0)
            b.minWidth = 0; b.minimumWidth = 0; b.minHeight = 0; b.minimumHeight = 0
            b.alpha = 0.9f
            sideButtons.addView(b, LinearLayout.LayoutParams(width, size).apply { bottomMargin = gap; gravity = Gravity.END })
        }
        add(ctx.flatButton("+") { map.controller.zoomIn() }.apply { textSize = 20f })
        add(ctx.flatButton("−") { map.controller.zoomOut() }.apply { textSize = 20f })
    }

    // ---------------------------------------------------------------- atualização

    override fun onShown() {
        host.requestLocationPermission()
        map.onResume()
        Gps.uiListener = { update() }
        Router.listener = { update() }
        refresh()
        update()
    }

    override fun onHidden() {
        Gps.uiListener = null
        Router.listener = null
        map.onPause()
    }

    /** A cada leitura do veículo: redesenha os mini-gráficos e o texto. */
    override fun refresh() {
        if (settingsRevision != Engine.settings.revision) rebuildWidgets()
        for (w in widgets) w.invalidate()
        updateInfo()
    }

    private fun setOverlay(m: Marker, lat: Double?, lon: Double?) {
        val present = map.overlays.contains(m)
        if (lat == null || lon == null) {
            if (present) map.overlays.remove(m)
            return
        }
        m.position = GeoPoint(lat, lon)
        if (!present) map.overlays.add(m)
    }

    /** A cada nova posição, direção ou mudança na viagem. */
    private fun update() {
        rebuildButtons()
        val f = Gps.fix
        val j = Journeys.active

        if (journeyRevision != Journeys.revision) {
            journeyRevision = Journeys.revision
            setOverlay(originMarker, j?.origin?.lat, j?.origin?.lon)
            setOverlay(destMarker, j?.dest?.lat, j?.dest?.lon)
            // Acrescenta só os pontos novos; refaz tudo se a viagem mudou.
            val t = Journeys.track
            if (t.size < drawnPoints || t.isEmpty()) { trackLine.setPoints(emptyList()); drawnPoints = 0 }
            while (drawnPoints < t.size) { trackLine.addPoint(GeoPoint(t[drawnPoints][0], t[drawnPoints][1])); drawnPoints++ }
        }

        // Quando a viagem ganha um destino com coordenadas, calcula a rota até ele uma vez.
        val jd = j?.dest
        if (jd?.lat != null && jd.lon != null && f != null && !Router.isBusy) {
            val key = jd.lat to jd.lon
            if (routedDest != key) { routedDest = key; Router.request(f.lat, f.lon, jd.lat, jd.lon, "destino da viagem") }
        }
        if (routeRevision != Router.revision) {
            routeRevision = Router.revision
            routeLine.setPoints(Router.route?.points?.map { GeoPoint(it[0], it[1]) } ?: emptyList())
        }
        applyHeadingUp()

        if (f != null) {
            setOverlay(me, f.lat, f.lon)
            if (!Gps.heading.isNaN()) me.rotation = -Gps.heading      // o osmdroid gira no sentido anti-horário
            // Mantém a seta por cima dos outros marcadores.
            if (map.overlays.lastOrNull() !== me) { map.overlays.remove(me); map.overlays.add(me) }
            val d = j?.dest
            if (d?.lat != null && d.lon != null && Router.route == null) destLine.setPoints(listOf(GeoPoint(f.lat, f.lon), GeoPoint(d.lat, d.lon)))
            else destLine.setPoints(emptyList())
            if (follow) {
                val p = GeoPoint(f.lat, f.lon)
                if (centeredOnce) map.controller.animateTo(p) else { map.controller.setCenter(p); centeredOnce = true }
            }
        } else {
            destLine.setPoints(emptyList())
        }
        compass.invalidate()
        updateInfo()
        map.invalidate()
    }

    private fun updateInfo() {
        val f = Gps.fix
        val j = Journeys.active
        val br = Locale("pt", "BR")
        val sb = StringBuilder()
        if (j != null) {
            sb.append(j.name).append(if (j.paused) "  ·  EM PAUSA" else "  ·  GRAVANDO").append('\n')
            sb.append(String.format(br, "%s  ·  %.1f km  ·  %.2f L", Journeys.hms(j.movingS), j.distKm, j.fuelL))
            if (!j.econKmL.isNaN()) sb.append(String.format(br, "  ·  %.1f km/L", j.econKmL))
            sb.append('\n')
            val d = j.dest
            if (f != null && d.lat != null && d.lon != null) {
                val dist = Gps.distanceM(f.lat, f.lon, d.lat, d.lon)
                val b = Gps.bearingDeg(f.lat, f.lon, d.lat, d.lon)
                sb.append(if (dist >= 1000) String.format(br, "Destino a %.1f km em linha reta", dist / 1000) else String.format(br, "Destino a %.0f m", dist))
                sb.append(String.format(br, " (rumo %.0f° %s)\n", b, Gps.cardinal(b)))
            } else {
                sb.append("Sem destino no mapa: toque e segure para marcar\n")
            }
        }
        val route = Router.route
        if (route != null) {
            Router.nextStep()?.let { (step, dist) ->
                val d = if (dist >= 1000) String.format(br, "%.1f km", dist / 1000) else String.format(br, "%.0f m", dist)
                sb.append("➜ ").append(step.text).append("  ·  ").append(d).append('\n')
            }
            sb.append(String.format(br, "Rota: faltam %.1f km  ·  %s\n", Router.remainingM() / 1000, Journeys.hms(Router.remainingS())))
        } else if (Router.status.isNotEmpty()) {
            sb.append(Router.status).append('\n')
        }
        if (f == null) {
            sb.append(if (Gps.hasPermission()) "Aguardando posição do GPS…" else "Permissão de localização negada")
        } else {
            if (j == null) sb.append(String.format(Locale.US, "Lat %.5f   Lon %.5f\n", f.lat, f.lon))
            val parts = ArrayList<String>()
            if (!f.altM.isNaN()) parts += String.format(br, "Alt %.0f m", f.altM)
            if (!f.speedKmh.isNaN()) parts += String.format(br, "%.0f km/h", f.speedKmh)
            if (!f.accuracyM.isNaN()) parts += String.format(br, "±%.0f m", f.accuracyM)
            sb.append(parts.joinToString("  ·  "))
        }
        val h = Gps.heading
        sb.append('\n').append(if (h.isNaN()) "Bússola: --" else String.format(br, "Bússola: %.0f° %s%s", h, Gps.cardinal(h), if (Gps.headingFromCompass) "" else " (pelo deslocamento)"))
        sb.append("\nMapa: ").append(orientationLabels[Engine.settings.mapOrientation.coerceIn(0, 2)]).append(" (toque na bússola)")
        info.text = sb
    }
}
