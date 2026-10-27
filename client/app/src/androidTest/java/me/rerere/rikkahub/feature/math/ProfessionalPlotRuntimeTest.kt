package me.rerere.rikkahub.feature.math

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.feature.interactive.*
import me.rerere.rikkahub.feature.material.SafeOfficeXml
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.*

class ProfessionalPlotRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext.also {check(it.packageName=="org.hxsi.agent.nativeqa")}
    private fun obj(raw: String)=Json.parseToJsonElement(raw).jsonObject
    @Test fun excludedRemovableHoleNeverGetsConnectedEvenOffTheSamplingGrid() {
        val plot=obj("""{"id":"plot","type":"math_plot","xMin":-1,"xMax":1,"yMin":0,"yMax":2,"curves":[{"kind":"function","label":"hole","expression":"(x-0.137)/(x-0.137)","exclude":[0.137]}],"points":[{"x":0.137,"y":1,"label":"undefined","open":true}]}""")
        val curve=MathPlot.sample(plot,emptyMap()).single()
        assertTrue(curve.segments.any {segment->segment.last().x<.137})
        assertTrue(curve.segments.any {segment->segment.first().x>.137})
        for(segment in curve.segments) {
            assertFalse(segment.first().x<.137 && segment.last().x>.137)
            for(point in segment) assertEquals(1.0,point.y,1e-12)
        }
        val pole=obj("""{"id":"plot","type":"math_plot","xMin":-1,"xMax":1,"yMin":-10,"yMax":10,"curves":[{"kind":"function","label":"pole","expression":"1/x"}]}""")
        for(segment in MathPlot.sample(pole,emptyMap()).single().segments) assertFalse(segment.first().x<0 && segment.last().x>0)
    }
    @Test fun realSpaceCurveRotationAndParametersPreserveKnownCoordinates() {
        val plot=obj("""{"id":"plot","type":"math_plot_3d","xMin":-1,"xMax":1,"yMin":-1,"yMax":1,"zMin":-1,"zMax":1,"azimuth":45,"elevation":30,"curves":[{"kind":"space_curve","label":"parameter line","x":"t","y":"0","z":"a*t","min":-1,"max":1}]}""")
        val positive=MathSpacePlot.sample(plot,mapOf("a" to 1.0)).single().segments.last().last()
        val negative=MathSpacePlot.sample(plot,mapOf("a" to -1.0)).single().segments.last().last()
        assertEquals(sqrt(.5),positive.x,1e-12)
        assertEquals(-.5*sqrt(.5)+sqrt(.75),positive.y,1e-12)
        assertEquals(-.5*sqrt(.5)-sqrt(.75),negative.y,1e-12)
        val surface=JsonObject(plot+("curves" to buildJsonArray {add(obj("""{"kind":"surface","label":"undefined half-domain","expression":"sqrt(x)"}"""))}))
        val sampledSurface=MathSpacePlot.sample(surface,emptyMap())
        for(curve in sampledSurface) for(segment in curve.segments) for(point in segment) assertTrue(point.x.isFinite() && point.y.isFinite())
        assertTrue(sampledSurface.flatMap {it.segments}.flatten().any {abs(it.x)<1e-12 && abs(it.y-(-sqrt(.5)+sqrt(.75)))<1e-12})
    }
    @Test fun exportedCircleStaysCircularAndPptxContainsActualEditableGeometry(): Unit = runBlocking {
        val plot=obj("""{"id":"plot","type":"math_plot","xMin":-2,"xMax":2,"yMin":-2,"yMax":2,"curves":[{"kind":"parametric","label":"unit circle","x":"cos(t)","y":"sin(t)","min":0,"max":6.283185307179586}]}""")
        val export=MathPlotExport("Unit circle",plot,emptyMap())
        val folder=File(context.cacheDir,"professional-math-exports").apply {mkdirs()}
        for(format in listOf("svg","pdf","pptx")) export.write(File(folder,"circle.$format"),format)
        val drawing="http://schemas.openxmlformats.org/drawingml/2006/main"
        ZipFile(File(folder,"circle.pptx")).use {archive ->
            val root=archive.getInputStream(archive.getEntry("ppt/slides/slide1.xml")).use {SafeOfficeXml.parse(it.readBytes())}
            assertEquals(0,root.getElementsByTagNameNS(drawing,"blip").length)
            val paths=root.getElementsByTagNameNS(drawing,"custGeom")
            val geometry=paths.item(paths.length-1) as Element
            val transform=(geometry.parentNode as Element).getElementsByTagNameNS(drawing,"xfrm").item(0) as Element
            val ext=transform.getElementsByTagNameNS(drawing,"ext").item(0) as Element
            val cx=ext.getAttribute("cx").toDouble();val cy=ext.getAttribute("cy").toDouble()
            val points=geometry.getElementsByTagNameNS(drawing,"pt")
            val coordinates=(0 until points.length).map {i -> val p=points.item(i) as Element;p.getAttribute("x").toDouble()/1e6*cx to p.getAttribute("y").toDouble()/1e6*cy}
            val centerX=(coordinates.maxOf {it.first}+coordinates.minOf {it.first})/2
            val centerY=(coordinates.maxOf {it.second}+coordinates.minOf {it.second})/2
            val radiusX=(coordinates.maxOf {it.first}-coordinates.minOf {it.first})/2
            val radiusY=(coordinates.maxOf {it.second}-coordinates.minOf {it.second})/2
            assertEquals(1.0,radiusX/radiusY,1e-5)
            assertEquals(120.0*6858000/700,radiusX,2.0)
            for((x,y) in coordinates) assertEquals(1.0,((x-centerX)/radiusX).pow(2)+((y-centerY)/radiusY).pow(2),1e-4)
        }
        val svg=File(folder,"circle.svg");svg.appendText("<!-- modified after delivery -->")
        assertTrue(runCatching {export.verify(svg,"svg")}.isFailure)
        val space=obj("""{"id":"plot","type":"math_plot_3d","xMin":-1,"xMax":1,"yMin":-1,"yMax":1,"zMin":-1,"zMax":1,"azimuth":35,"elevation":25,"curves":[{"kind":"surface","label":"saddle","expression":"x^2-y^2"}]}""")
        val spaceExport=MathPlotExport("Saddle surface",space,emptyMap())
        for(format in listOf("png","svg","pdf","pptx")) spaceExport.write(File(folder,"surface.$format"),format)
    }
}
