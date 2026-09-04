package com.ailenezareti.nezaretv4.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ailenezareti.nezaretv4.Prefs
import com.ailenezareti.nezaretv4.api.ApiClient
import com.ailenezareti.nezaretv4.databinding.FragmentMapBinding
import com.ailenezareti.nezaretv4.model.LocationPoint
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import kotlin.math.*

class MapFragment : Fragment() {
    private var _b: FragmentMapBinding? = null
    private val b get() = _b!!
    private var latestPoint: GeoPoint? = null
    private var points: List<LocationPoint> = emptyList()
    private var routeShown = false
    private var selectedDate: String? = null
    private var satellite = false
    private lateinit var sheet: BottomSheetBehavior<View>

    private val esri: ITileSource = object : XYTileSource("Esri",0,19,256,".jpg", arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")) {
        override fun getTileURLString(index: Long): String {
            val z=MapTileIndex.getZoom(index); val x=MapTileIndex.getX(index); val y=MapTileIndex.getY(index)
            return "$baseUrl$z/$y/$x$mImageFilenameEnding"
        }
    }

    override fun onCreateView(inflater:LayoutInflater,container:ViewGroup?,state:Bundle?):View {
        _b=FragmentMapBinding.inflate(inflater,container,false); return b.root
    }

    override fun onViewCreated(view:View,state:Bundle?) {
        Configuration.getInstance().userAgentValue=requireContext().packageName
        b.map.setTileSource(TileSourceFactory.MAPNIK)
        b.map.setMultiTouchControls(true)
        b.map.controller.setZoom(19.0)
        sheet=BottomSheetBehavior.from(b.bottomSheet)
        sheet.peekHeight=(58*resources.displayMetrics.density).toInt()
        sheet.isHideable=false
        sheet.isFitToContents=true
        sheet.state=BottomSheetBehavior.STATE_COLLAPSED

        b.mapUserCard.setOnClickListener { sheet.state=BottomSheetBehavior.STATE_EXPANDED }
        b.layers.setOnClickListener { satellite=!satellite; b.map.setTileSource(if(satellite) esri else TileSourceFactory.MAPNIK); b.map.invalidate() }
        b.target.setOnClickListener { latestPoint?.let { b.map.controller.animateTo(it); b.map.controller.setZoom(19.0) } }
        b.refresh.setOnClickListener { load() }
        b.route.setOnClickListener { routeShown=!routeShown; drawMap(); b.route.text=if(routeShown)"Marşrutu gizlət" else "Marşrut"; sheet.state=BottomSheetBehavior.STATE_COLLAPSED }
        b.history.setOnClickListener { pickDate() }
        b.share.setOnClickListener { latestPoint?.let { p ->
            val text="${b.mapChild.text}: https://maps.google.com/?q=${p.latitude},${p.longitude}"
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},"Mövqeyi paylaş"))
        } }
        b.googleRoute.setOnClickListener { openNavigation() }
        load()
    }

    private fun pickDate(){ val c=Calendar.getInstance(); DatePickerDialog(requireContext(),{_,y,m,d->
        selectedDate=String.format(Locale.US,"%04d-%02d-%02d",y,m+1,d); routeShown=true; b.route.text="Marşrutu gizlət"; load(); sheet.state=BottomSheetBehavior.STATE_COLLAPSED
    },c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show() }

    private fun openNavigation(){ latestPoint?.let { p ->
        val uri=Uri.parse("google.navigation:q=${p.latitude},${p.longitude}&mode=d")
        val g=Intent(Intent.ACTION_VIEW,uri).apply{setPackage("com.google.android.apps.maps")}
        if(g.resolveActivity(requireContext().packageManager)!=null) startActivity(g)
        else startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${p.latitude},${p.longitude}")))
    } }

    private fun load(){ val id=Prefs.child(requireContext()); if(id<0)return
        viewLifecycleOwner.lifecycleScope.launch { try {
            val api=ApiClient.service(requireContext())
            val child=api.children().body()?.children?.firstOrNull{it.id==id}
            b.mapChild.text=child?.name?:"Uşaq"; b.mapAvatar.text=child?.name?.trim()?.firstOrNull()?.uppercaseChar()?.toString()?:"U"
            points=if(selectedDate==null) api.locations(id,"today").body()?.locations.orEmpty()
            else api.locations(id,"custom","$selectedDate 00:00:00","$selectedDate 23:59:59").body()?.locations.orEmpty()
            if(points.isEmpty()){Toast.makeText(requireContext(),"Bu tarix üçün GPS yoxdur",Toast.LENGTH_SHORT).show();return@launch}
            points=points.sortedBy{it.recorded_at}; val last=points.last(); latestPoint=GeoPoint(last.latitude.toDouble(),last.longitude.toDouble())
            b.mapStatus.text="Son mövqe • ${displayDateTime(last.recorded_at)}"
            b.mapBattery.text=last.battery_pct?.let{"▮ $it%"}?:"—"
            b.mapAddress.text="${"%.5f".format(last.latitude.toDouble())}, ${"%.5f".format(last.longitude.toDouble())}"
            b.mapMeta.text="Dəqiqlik ${last.accuracy_m?:"—"} m • ${displayDateTime(last.recorded_at)} • Batareya ${last.battery_pct?:"—"}%"
            drawMap(); b.map.controller.setCenter(latestPoint); b.map.controller.setZoom(19.0)
        }catch(e:Exception){Toast.makeText(requireContext(),"Xəritə məlumatı yüklənmədi",Toast.LENGTH_SHORT).show()} }
    }

    private fun drawMap(){ if(_b==null||points.isEmpty())return; b.map.overlays.clear(); var km=0.0; var accepted=1
        if(routeShown&&points.size>1){
            val goodPts=mutableListOf<GeoPoint>(); val goodData=mutableListOf<LocationPoint>(); var prev=points.first(); goodPts.add(gp(prev)); goodData.add(prev)
            for(i in 1 until points.size){ val cur=points[i]; val a=gp(prev); val c=gp(cur); val d=dist(a,c); val sec=timeDiff(prev.recorded_at,cur.recorded_at); val speed=if(sec>0)d/sec*3.6 else 0.0
                if(d<=3000||speed<=180){goodPts.add(c);goodData.add(cur);km+=d/1000.0;accepted++}; prev=cur }
            if(goodPts.size>1){ b.map.overlays.add(Polyline().apply{setPoints(goodPts);outlinePaint.color=Color.parseColor("#2478F3");outlinePaint.strokeWidth=8f;outlinePaint.strokeCap=Paint.Cap.ROUND})
                b.map.overlays.add(Marker(b.map).apply{position=goodPts.first();title="Başlanğıc";snippet=displayDateTime(goodData.first().recorded_at);setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_CENTER);icon=dot("#17C979",30)})
                val step=max(1,goodData.size/80)
                for(i in goodData.indices step step){ val x=goodData[i]; b.map.overlays.add(Marker(b.map).apply{position=gp(x);title="GPS nöqtəsi ${i+1}";snippet="${displayDateTime(x.recorded_at)} • Dəqiqlik ${x.accuracy_m?:"—"}m • Batareya ${x.battery_pct?:"—"}%";setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_CENTER);icon=dot("#2478F3",20)}) }
                addStopMarkers(goodData)
            }
        }
        val p=latestPoint?:return
        b.map.overlays.add(Marker(b.map).apply{position=p;title=b.mapChild.text.toString();snippet="Son GPS • ${displayDateTime(points.last().recorded_at)} • Batareya ${points.last().battery_pct?:"—"}%";setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM);icon=avatar(b.mapAvatar.text.toString())})
        b.mapSummary.text=if(routeShown)"${selectedDate?:"Bu gün"} • ${String.format("%.1f km",km)} • $accepted GPS nöqtə • nöqtəyə bas: vaxt/batareya" else "Yuxarıdakı User kartına basaraq paneli aç • Son mövqe göstərilir"
        b.map.invalidate()
    }

    private fun addStopMarkers(data:List<LocationPoint>){ if(data.size<2)return; var start=0
        for(i in 1..data.size){ val end=i==data.size; val moved=if(!end) dist(gp(data[start]),gp(data[i]))>50 else true
            if(moved){ if(i-start>1){ val secs=timeDiff(data[start].recorded_at,data[i-1].recorded_at); if(secs>=180){ val m=(secs/60).roundToInt(); b.map.overlays.add(Marker(b.map).apply{position=gp(data[start]);title="Dayanma • ${formatMinutes(m)}";snippet="${displayDateTime(data[start].recorded_at)} → ${displayDateTime(data[i-1].recorded_at)}";setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_CENTER);icon=dot("#F79009",28)}) } }; start=i.coerceAtMost(data.lastIndex) }
        }
    }

    private fun gp(x:LocationPoint)=GeoPoint(x.latitude.toDouble(),x.longitude.toDouble())
    private fun timeDiff(a:String,b:String):Double=try{val f=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");java.time.Duration.between(LocalDateTime.parse(a,f),LocalDateTime.parse(b,f)).seconds.toDouble()}catch(_:Exception){0.0}
    private fun displayDateTime(raw:String):String=try{LocalDateTime.parse(raw,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).plusHours(1).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))}catch(_:Exception){raw}
    private fun formatMinutes(m:Int)=if(m>=60)"${m/60}s ${m%60}dəq" else "$m dəq"
    private fun dist(a:GeoPoint,c:GeoPoint):Double{val r=6371000.0;val p1=Math.toRadians(a.latitude);val p2=Math.toRadians(c.latitude);val dp=Math.toRadians(c.latitude-a.latitude);val dl=Math.toRadians(c.longitude-a.longitude);val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2);return 2*r*atan2(sqrt(h),sqrt(1-h))}

    private fun avatar(letter:String):BitmapDrawable{val s=104;val bmp=Bitmap.createBitmap(s,s,Bitmap.Config.ARGB_8888);val c=Canvas(bmp);val w=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE};val blue=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#2478F3")};c.drawCircle(s/2f,s/2f-5,s/2f-5,w);c.drawCircle(s/2f,s/2f-5,s/2f-11,blue);val t=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=38f;textAlign=Paint.Align.CENTER;isFakeBoldText=true};c.drawText(letter,s/2f,s/2f-5-(t.ascent()+t.descent())/2,t);val p=Path().apply{moveTo(s/2f-10,s-22f);lineTo(s/2f+10,s-22f);lineTo(s/2f,s-4f);close()};c.drawPath(p,blue);return BitmapDrawable(resources,bmp)}
    private fun dot(hex:String,size:Int):BitmapDrawable{val bmp=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);val c=Canvas(bmp);val w=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE};val d=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor(hex)};c.drawCircle(size/2f,size/2f,size/2f,w);c.drawCircle(size/2f,size/2f,size/2f-4,d);return BitmapDrawable(resources,bmp)}
    override fun onResume(){super.onResume();b.map.onResume()}; override fun onPause(){b.map.onPause();super.onPause()}; override fun onDestroyView(){super.onDestroyView();_b=null}
}
