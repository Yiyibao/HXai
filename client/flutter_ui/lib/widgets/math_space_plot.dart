import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/gestures.dart';
import 'math_plot.dart';

typedef SpaceVertex = (double, double, double);
class SpaceCurve {
  final String label;
  final List<List<SpaceVertex>> segments;
  SpaceCurve(this.label, this.segments);
}
class MathSpacePlot {
  static const fields = {'xMin','xMax','yMin','yMax','zMin','zMax','azimuth','elevation','curves'};
  static void validate(Map<String,dynamic> plot, Set<String> parameters) {
    if (plot.keys.any((key) => !{...fields,'id','type'}.contains(key)) ||
        parameters.any({'x','y','z','t','pi','e','sin','cos','tan','sqrt','abs','ln','log','exp'}.contains)) {
      throw const FormatException('三维图形字段或参数无效');
    }
    for (final axis in ['x','y','z']) {
      final range = MathPlot.number(plot,'${axis}Max') - MathPlot.number(plot,'${axis}Min');
      if (range < 1e-4 || range > 1e6) throw const FormatException('三维坐标域无效');
    }
    if (MathPlot.number(plot,'azimuth').abs() > 360 || MathPlot.number(plot,'elevation').abs() > 85) throw const FormatException('观察角度无效');
    final curves = plot['curves'];
    if (curves is! List || curves.isEmpty || curves.length > 4) throw const FormatException('三维曲线数量无效');
    for (final item in curves) {
      final c = Map<String,dynamic>.from(item as Map);
      final surface = c['kind'] == 'surface';
      final keys = surface ? {'kind','label','expression'} : {'kind','label','x','y','z','min','max'};
      if ((!surface && c['kind'] != 'space_curve') || c.keys.toSet().difference(keys).isNotEmpty || keys.difference(c.keys.toSet()).isNotEmpty || c['label'] is! String || (c['label'] as String).isEmpty || (c['label'] as String).length > 100) throw const FormatException('三维曲线定义无效');
      for (final key in surface ? ['expression'] : ['x','y','z']) {
        MathExpression.parse(c[key] as String, {...parameters,...(surface ? {'x','y'} : {'t'})});
      }
      if (!surface && MathPlot.number(c,'max') <= MathPlot.number(c,'min')) throw const FormatException('空间曲线参数域无效');
    }
  }
  static List<MathCurve> sample(Map<String,dynamic> plot, Map<String,double> parameters) {
    final a=MathPlot.number(plot,'azimuth')*math.pi/180, e=MathPlot.number(plot,'elevation')*math.pi/180;
    final ca=math.cos(a),sa=math.sin(a),ce=math.cos(e),se=math.sin(e);
    return sampleWorld(plot,parameters).map((curve)=>MathCurve(curve.label,curve.segments.map((segment)=>segment.map((p)=>Offset(ca*p.$1-sa*p.$2,-se*(sa*p.$1+ca*p.$2)+ce*p.$3)).toList()).toList())).toList();
  }
  static List<SpaceCurve> sampleWorld(Map<String,dynamic> plot, Map<String,double> parameters) {
    validate(plot,parameters.keys.toSet());
    if (parameters.values.any((v) => !v.isFinite || v.abs() > 1e12)) throw const FormatException('参数无效');
    final x0 = MathPlot.number(plot,'xMin'), x1 = MathPlot.number(plot,'xMax');
    final y0 = MathPlot.number(plot,'yMin'), y1 = MathPlot.number(plot,'yMax');
    final z0 = MathPlot.number(plot,'zMin'), z1 = MathPlot.number(plot,'zMax');
    return (plot['curves'] as List).map((item) {
      final c = Map<String,dynamic>.from(item as Map), surface = item['kind'] == 'surface';
      final variables = {...parameters.keys,...(surface ? {'x','y'} : {'t'})};
      final fx = surface ? null : MathExpression.parse(c['x'] as String,variables);
      final fy = surface ? null : MathExpression.parse(c['y'] as String,variables);
      final fz = MathExpression.parse(c[surface ? 'expression' : 'z'] as String,variables);
      final environment = {...parameters};
      final segments = <List<SpaceVertex>>[];
      void trace(int count, (double,double,double) Function(double) point) {
        var run = <SpaceVertex>[]; SpaceVertex? previous;
        void flush() { if (run.length >= 2) {segments.add(run);run=[];} else {run.clear();} previous = null; }
        bool jumps(double mid,double now,double before,double range) => !mid.isFinite || (mid-(now+before)/2).abs()>math.max(1e-8,math.max(mid.abs(),math.max(now.abs(),before.abs()))*.35+range*1e-7);
        for (var i=0;i<=count;i++) {
          final (x,y,z) = point(i/count);
          if (!x.isFinite || !y.isFinite || !z.isFinite || x<x0 || x>x1 || y<y0 || y>y1 || z<z0 || z>z1) { flush(); continue; }
          if (previous != null) {
            final middle = point((i-.5)/count), before=previous!;
            if (jumps(middle.$1,x,before.$1,x1-x0) || jumps(middle.$2,y,before.$2,y1-y0) || jumps(middle.$3,z,before.$3,z1-z0)) flush();
          }
          run.add((2*(x-x0)/(x1-x0)-1,2*(y-y0)/(y1-y0)-1,2*(z-z0)/(z1-z0)-1)); previous=(x,y,z);
        }
        flush();
      }
      if (surface) {
        for (var axis=0;axis<2;axis++) {
          for (var line=0;line<=20;line++) {
            trace(60,(fraction) {
              final x=x0+(x1-x0)*(axis==0?fraction:line/20), y=y0+(y1-y0)*(axis==0?line/20:fraction);
              environment['x']=x; environment['y']=y;
              return (x,y,fz.evaluate(environment));
            });
          }
        }
      } else {
        final low=MathPlot.number(c,'min'), high=MathPlot.number(c,'max');
        trace(800,(fraction) { environment['t']=low+(high-low)*fraction; return (fx!.evaluate(environment),fy!.evaluate(environment),fz.evaluate(environment)); });
      }
      return SpaceCurve(c['label'] as String,segments);
    }).toList();
  }
}
List<SpaceCurve> _sampleSpace(Map<String,dynamic> request) => MathSpacePlot.sampleWorld(Map<String,dynamic>.from(request['definition'] as Map),Map<String,double>.from(request['parameters'] as Map));

class MathSpacePlotView extends StatefulWidget {
  final Map<String,dynamic> definition;
  final Map<String,double> parameters;
  const MathSpacePlotView({super.key,required this.definition,required this.parameters});
  @override State<MathSpacePlotView> createState() => _MathSpacePlotViewState();
}
class _MathSpacePlotViewState extends State<MathSpacePlotView> {
  late double azimuth,elevation;
  List<SpaceCurve> curves=[];
  double zoom=1, gestureZoom=1;
  Timer? debounce;
  int revision=0;
  bool computing=true;
  String? error;
  late String signature;
  @override void initState() { super.initState(); azimuth=MathPlot.number(widget.definition,'azimuth'); elevation=MathPlot.number(widget.definition,'elevation'); update(); }
  @override void didUpdateWidget(covariant MathSpacePlotView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (signature!=jsonEncode([widget.definition,widget.parameters])) {
      if (oldWidget.definition['azimuth']!=widget.definition['azimuth']) azimuth=MathPlot.number(widget.definition,'azimuth');
      if (oldWidget.definition['elevation']!=widget.definition['elevation']) elevation=MathPlot.number(widget.definition,'elevation');
      update();
    }
  }
  void update() {
    signature=jsonEncode([widget.definition,widget.parameters]);
    final token=++revision; debounce?.cancel(); computing=true; error=null;
    final definition={...widget.definition,'azimuth':azimuth,'elevation':elevation};
    debounce=Timer(const Duration(milliseconds:50),() async {
      try {
        final sampled=await compute(_sampleSpace,{'definition':definition,'parameters':widget.parameters});
        if (!mounted || token!=revision) return;
        setState(() {curves=sampled; computing=false;});
      } catch (exception) { if (mounted && token==revision) setState(() {error=exception.toString();computing=false;curves=[];}); }
    });
  }
  void rotate(double a,double e) => setState(() { azimuth=a.clamp(-360,360);elevation=e.clamp(-85,85); });
  @override void dispose() { ++revision;debounce?.cancel();super.dispose(); }
  @override Widget build(BuildContext context) {
    final color=Theme.of(context).colorScheme.onSurface;
    return Column(crossAxisAlignment:CrossAxisAlignment.stretch,children:[
      const Text('三维数值图形 · 拖动旋转 · 滚轮/双指缩放；不是数学证明'),
      for (final c in widget.definition['curves'] as List) SelectableText(c['kind']=='surface'?'${c['label']}: z = ${c['expression']}':'${c['label']}: (${c['x']}, ${c['y']}, ${c['z']})'),
      if (error!=null) Text(error!,style:TextStyle(color:Theme.of(context).colorScheme.error)),
      Semantics(label:'三维曲面与空间曲线。下方方位角和仰角滑块可替代拖动旋转。',child: SizedBox(height:340,child:LayoutBuilder(builder:(context,constraints) => GestureDetector(
        onScaleStart:(_) { gestureZoom=zoom; },
        onScaleUpdate:(event) { if (event.pointerCount==1) {rotate(azimuth+event.focalPointDelta.dx*.6,elevation+event.focalPointDelta.dy*.4);} else {setState(()=>zoom=(gestureZoom*event.scale).clamp(.7,5));} },
        child:ClipRect(child:Listener(onPointerSignal:(event) {if(event is PointerScrollEvent) setState(()=>zoom=(zoom*math.exp(-event.scrollDelta.dy*.001)).clamp(.7,5));},
          child:Transform.scale(scale:zoom,child:CustomPaint(size:Size(constraints.maxWidth,340),painter:_SpacePainter(curves,color,azimuth,elevation))))),
      )))),
      if (computing) const LinearProgressIndicator(),
      Text('方位角 ${azimuth.toStringAsFixed(1)}°'),
      Slider(semanticFormatterCallback:(v) => '方位角 ${v.round()}度',value:azimuth,min:-360,max:360,onChanged:(v)=>rotate(v,elevation)),
      Text('仰角 ${elevation.toStringAsFixed(1)}°'),
      Slider(semanticFormatterCallback:(v) => '仰角 ${v.round()}度',value:elevation,min:-85,max:85,onChanged:(v)=>rotate(azimuth,v)),
      Text('缩放 ${zoom.toStringAsFixed(2)}×'),
      Slider(semanticFormatterCallback:(v)=>'缩放 ${v.toStringAsFixed(1)}倍',value:zoom,min:.7,max:5,onChanged:(v)=>setState(()=>zoom=v)),
      Text('x∈[${widget.definition['xMin']},${widget.definition['xMax']}], y∈[${widget.definition['yMin']},${widget.definition['yMax']}], z∈[${widget.definition['zMin']},${widget.definition['zMax']}]；导出为定义默认视角的固定版本。'),
    ]);
  }
}
class _SpacePainter extends CustomPainter {
  final List<SpaceCurve> curves; final Color foreground; final double azimuth,elevation;
  _SpacePainter(this.curves,this.foreground,this.azimuth,this.elevation);
  @override void paint(Canvas canvas,Size size) {
    final scale=math.min(size.width/3.6,size.height/3.6);
    Offset at(Offset p)=>Offset(size.width/2+p.dx*scale,size.height/2-p.dy*scale);
    final paint=Paint()..style=PaintingStyle.stroke..strokeWidth=1.3;
    final a=azimuth*math.pi/180,e=elevation*math.pi/180;
    final ca=math.cos(a),sa=math.sin(a),ce=math.cos(e),se=math.sin(e);
    Offset project(SpaceVertex p)=>at(Offset(ca*p.$1-sa*p.$2,-se*(sa*p.$1+ca*p.$2)+ce*p.$3));
    final axes=[Offset(math.cos(a),-math.sin(e)*math.sin(a)),Offset(-math.sin(a),-math.sin(e)*math.cos(a)),Offset(0,math.cos(e))];
    paint.color=foreground.withValues(alpha:.45);
    for(var i=0;i<3;i++) {
      canvas.drawLine(at(axes[i]*-1),at(axes[i]),paint);
      final label=TextPainter(text:TextSpan(text:['x','y','z'][i],style:TextStyle(color:foreground,fontSize:13)),textDirection:TextDirection.ltr)..layout();
      label.paint(canvas,at(axes[i]));
    }
    for(var index=0;index<curves.length;index++) {
      paint.color=MathPlotView.colors[index];
      for(final segment in curves[index].segments) {
        final path=Path();
        for(var i=0;i<segment.length;i++) { final p=project(segment[i]); if(i==0) {path.moveTo(p.dx,p.dy);} else {path.lineTo(p.dx,p.dy);} }
        canvas.drawPath(path,paint);
      }
    }
  }
  @override bool shouldRepaint(covariant _SpacePainter oldDelegate)=>oldDelegate.curves!=curves || oldDelegate.foreground!=foreground || oldDelegate.azimuth!=azimuth || oldDelegate.elevation!=elevation;
}
