package com.ailenezareti.nezaretv4.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ailenezareti.nezaretv4.Prefs
import com.ailenezareti.nezaretv4.R
import com.ailenezareti.nezaretv4.api.ApiClient
import com.ailenezareti.nezaretv4.databinding.FragmentHomeBinding
import com.ailenezareti.nezaretv4.model.GeoZone
import com.ailenezareti.nezaretv4.model.LocationPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

class HomeFragment : Fragment() {
    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!
    private var lastLocations: List<LocationPoint> = emptyList()
    private var lastZones: List<GeoZone> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _b = FragmentHomeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        b.shortcutMap.setOnClickListener { (activity as? MainActivity)?.navigate(R.id.nav_map) }
        b.shortcutCalls.setOnClickListener { (activity as? MainActivity)?.navigate(R.id.nav_calls) }
        b.shortcutZones.setOnClickListener { (activity as? MainActivity)?.navigate(R.id.nav_zones) }
        b.shortcutAlerts.setOnClickListener { (activity as? MainActivity)?.navigate(R.id.nav_more) }
        b.bellBtn.setOnClickListener { (activity as? MainActivity)?.navigate(R.id.nav_more) }
        b.batteryCard.setOnClickListener { showBatteryAnalysis() }
        b.zoneCard.setOnClickListener { showZoneAnalysis() }
        load()
    }

    private fun distance(a: GeoPoint, c: GeoPoint): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(a.latitude); val p2 = Math.toRadians(c.latitude)
        val dp = Math.toRadians(c.latitude-a.latitude); val dl = Math.toRadians(c.longitude-a.longitude)
        val h = sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2)
        return 2*r*atan2(sqrt(h),sqrt(1-h))
    }

    private fun load() {
        val id = Prefs.child(requireContext())
        if (id < 0) { viewLifecycleOwner.lifecycleScope.launch { delay(700); if (_b != null) load() }; return }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val api = ApiClient.service(requireContext())
                val children = api.children().body()?.children.orEmpty()
                val child = children.firstOrNull { it.id == id } ?: children.firstOrNull()
                b.childName.text = child?.name ?: "Uşaq"
                b.avatar.text = child?.name?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
                b.lastSeen.text = child?.last_seen?.let { "Son görülmə: ${displayDateTime(it)}" } ?: "Son görülmə gözlənilir"

                val loc = api.locations(id, "today").body()?.locations.orEmpty().sortedBy { it.recorded_at }
                lastLocations = loc
                val latest = loc.lastOrNull()
                b.todayPoints.text = loc.size.toString()
                b.batteryTop.text = latest?.battery_pct?.let { "▮ $it%" } ?: "—"
                val battery = latest?.battery_pct ?: 0
                b.batteryAnalysis.text = latest?.battery_pct?.let { "$it%" } ?: "—%"
                b.batteryProgress.progress = battery.coerceIn(0,100)
                b.batteryAnalysisSub.text = when { battery == 0 -> "Məlumat yoxdur"; battery <= 20 -> "Aşağı səviyyə"; battery <= 50 -> "Orta səviyyə"; else -> "Normal səviyyə" }
                b.activityLocation.text = latest?.let { "●  Mövqe yeniləndi  •  ${displayTime(it.recorded_at)}" } ?: "●  Mövqe məlumatı yoxdur"

                var meters = 0.0
                for (i in 1 until loc.size) {
                    val a = GeoPoint(loc[i-1].latitude.toDouble(), loc[i-1].longitude.toDouble())
                    val c = GeoPoint(loc[i].latitude.toDouble(), loc[i].longitude.toDouble())
                    val d = distance(a,c)
                    if (d < 3000) meters += d
                }
                b.todayDistance.text = String.format("%.1f km", meters/1000.0)

                val today = LocalDate.now().toString()
                val calls = api.calls(id,today,today,"all",null,1000,0).body()?.calls.orEmpty()
                b.todayCalls.text = calls.size.toString()
                val incoming = calls.count { it.call_type.equals("incoming",true) || it.call_type == "1" }
                val outgoing = calls.count { it.call_type.equals("outgoing",true) || it.call_type == "2" }
                val missed = calls.count { it.call_type.equals("missed",true) || it.call_type == "3" }
                b.callAnalysisTotal.text = "${calls.size} zəng"
                b.incomingCount.text = incoming.toString(); b.outgoingCount.text = outgoing.toString(); b.missedCount.text = missed.toString()
                val most = calls.groupBy { it.phone_number.filter { ch -> ch.isDigit() || ch=='+' } }
                    .mapValues { e -> e.value.sumOf { it.duration_sec.coerceAtLeast(0) } }
                    .maxByOrNull { it.value }
                b.topNumber.text = if (most == null) "Ən çox danışılan: —" else "Ən çox danışılan: ${most.key} • ${formatDuration(most.value)}"
                val latestCall = calls.maxByOrNull { it.occurred_at }
                b.activityCall.text = latestCall?.let { "☎  ${it.contact_name?.takeIf { n -> n.isNotBlank() } ?: it.phone_number}  •  ${displayTime(it.occurred_at)}" } ?: "☎  Son zəng yoxdur"

                val zones = api.zones(id).body()?.zones.orEmpty()
                lastZones = zones
                val activeZones = zones.filter { it.is_active == 1 }
                val currentZone = latest?.let { l -> activeZones.firstOrNull { inside(l,it) } }
                b.zoneAnalysis.text = currentZone?.name ?: "Zona xaricində"
                val dwell = currentZone?.let { zoneDwellMinutes(loc,it) } ?: 0
                b.zoneAnalysisSub.text = if (currentZone != null) "Bu gün bu zonada təx. ${formatMinutes(dwell)}" else "${activeZones.size} aktiv zona"

                val alerts = api.alerts(id).body()?.alerts.orEmpty()
                val zoneAlert = alerts.firstOrNull { it.alert_type.contains("zone",true) }
                b.activityZone.text = zoneAlert?.let { "⌖  ${it.message}" } ?: "⌖  Zona bildirişi yoxdur"
            } catch (_: Exception) { }
        }
    }

    private fun showBatteryAnalysis() {
        if (lastLocations.isEmpty()) return
        val rows = lastLocations.filter { it.battery_pct != null }.takeLast(40)
        val text = buildString {
            val last = rows.lastOrNull()
            append("Son GPS nöqtəsi\n")
            if (last != null) append("${displayDateTime(last.recorded_at)} • ${last.latitude}, ${last.longitude} • ${last.battery_pct}%\n\n")
            append("Batareya tarixçəsi\n\n")
            var prev: Int? = null
            rows.forEach { p ->
                val now = p.battery_pct ?: return@forEach
                val arrow = when { prev == null -> "•"; now > prev!! -> "↑"; now < prev!! -> "↓"; else -> "→" }
                append("$arrow ${displayDateTime(p.recorded_at)}   $now%   GPS ${p.latitude}, ${p.longitude}\n")
                prev = now
            }
        }
        showTextDialog("Batareya + GPS analizi", text)
    }

    private fun showZoneAnalysis() {
        if (lastZones.isEmpty()) return
        var totalMeters = 0.0
        for (i in 1 until lastLocations.size) {
            val a = GeoPoint(lastLocations[i-1].latitude.toDouble(), lastLocations[i-1].longitude.toDouble())
            val c = GeoPoint(lastLocations[i].latitude.toDouble(), lastLocations[i].longitude.toDouble())
            val d = distance(a,c); if (d < 3000) totalMeters += d
        }
        val text = buildString {
            append("Bu gün gedilən yol: ${String.format("%.1f",totalMeters/1000.0)} km\n\n")
            lastZones.filter { it.is_active == 1 }.forEach { z ->
                val mins = zoneDwellMinutes(lastLocations,z)
                val samples = lastLocations.filter { inside(it,z) }
                val last = samples.lastOrNull()
                append("${z.name}\n")
                append("• Zonada qalma: ${formatMinutes(mins)}\n")
                append("• GPS qeydi: ${samples.size}\n")
                append("• Son görünmə: ${last?.let { displayDateTime(it.recorded_at) } ?: "—"}\n")
                append("• Mərkəz: ${z.latitude}, ${z.longitude}\n\n")
            }
        }
        showTextDialog("Zona analizi", text)
    }

    private fun showTextDialog(title:String, text:String) {
        val tv = TextView(requireContext()).apply { this.text=text; textSize=14f; setPadding(42,24,42,24); setTextColor(resources.getColor(R.color.text,null)) }
        val scroll = ScrollView(requireContext()).apply { addView(tv) }
        AlertDialog.Builder(requireContext()).setTitle(title).setView(scroll).setPositiveButton("Bağla",null).show()
    }

    private fun inside(p:LocationPoint,z:GeoZone):Boolean = try {
        distance(GeoPoint(p.latitude.toDouble(),p.longitude.toDouble()), GeoPoint(z.latitude.toDouble(),z.longitude.toDouble())) <= z.radius_m
    } catch (_:Exception){ false }

    private fun zoneDwellMinutes(loc:List<LocationPoint>, z:GeoZone):Int {
        var sec=0.0
        for(i in 1 until loc.size){ if(inside(loc[i-1],z) && inside(loc[i],z)) sec += timeDiff(loc[i-1].recorded_at,loc[i].recorded_at).coerceIn(0.0,900.0) }
        return (sec/60.0).roundToInt()
    }

    private fun timeDiff(a:String,b:String):Double = try { val f=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"); java.time.Duration.between(LocalDateTime.parse(a,f),LocalDateTime.parse(b,f)).seconds.toDouble() } catch (_:Exception){0.0}
    private fun displayDateTime(raw:String):String = try { LocalDateTime.parse(raw,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).plusHours(1).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) } catch (_:Exception){raw}
    private fun displayTime(raw:String):String = try { LocalDateTime.parse(raw,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).plusHours(1).format(DateTimeFormatter.ofPattern("HH:mm")) } catch (_:Exception){raw.takeLast(8).take(5)}
    private fun formatDuration(sec:Int):String { val m=sec/60; val s=sec%60; return if(m>0) "$m dəq ${s} san" else "$s san" }
    private fun formatMinutes(m:Int):String = if(m>=60) "${m/60} saat ${m%60} dəq" else "$m dəq"

    override fun onDestroyView(){ super.onDestroyView(); _b=null }
}
