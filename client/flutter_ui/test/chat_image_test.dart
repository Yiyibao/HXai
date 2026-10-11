import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/widgets/chat_image.dart';
import 'package:flutter_ui/widgets/enhanced_image_view.dart';
import 'package:flutter_ui/widgets/interactive_surface.dart';
import 'package:flutter_ui/widgets/math_markdown.dart';
import 'package:flutter_ui/widgets/message_bubble.dart';

class ImageClient extends Fake implements HttpClient {
  final Uint8List bytes;
  ImageClient(this.bytes);
  @override
  Future<HttpClientRequest> getUrl(Uri url) async => ImageRequest(bytes);
}

class ImageRequest extends Fake implements HttpClientRequest {
  final Uint8List bytes;
  ImageRequest(this.bytes);
  @override
  Future<HttpClientResponse> close() async => ImageResponse(bytes);
}

class ImageResponse extends Stream<List<int>> implements HttpClientResponse {
  final Uint8List bytes;
  ImageResponse(this.bytes);
  @override
  int get statusCode => 200;
  @override
  int get contentLength => bytes.length;
  @override
  HttpClientResponseCompressionState get compressionState =>
      HttpClientResponseCompressionState.notCompressed;
  @override
  StreamSubscription<List<int>> listen(
    void Function(List<int>)? onData, {
    Function? onError,
    void Function()? onDone,
    bool? cancelOnError,
  }) => Stream<List<int>>.value(bytes).listen(
    onData,
    onError: onError,
    onDone: onDone,
    cancelOnError: cancelOnError,
  );
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  late Uint8List png;
  late String dataUrl;
  final boundary = GlobalKey();

  setUpAll(() async {
    final recorder = ui.PictureRecorder();
    Canvas(recorder).drawRect(
      const Rect.fromLTWH(0, 0, 120, 60),
      Paint()..color = Colors.blue,
    );
    final picture = recorder.endRecording();
    final image = await picture.toImage(120, 60);
    png = (await image.toByteData(format: ui.ImageByteFormat.png))!.buffer
        .asUint8List();
    dataUrl = 'data:image/png;base64,${base64Encode(png)}';
    image.dispose();
    picture.dispose();
  });

  Future<void> render(WidgetTester tester, Widget child) async {
    await tester.runAsync(() async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: RepaintBoundary(
              key: boundary,
              child: SingleChildScrollView(child: child),
            ),
          ),
        ),
      );
      // Wait for the engine to decode, not merely for a widget to exist.
      final images = tester.widgetList<Image>(find.byType(Image)).toList();
      for (final image in images) {
        await precacheImage(
          image.image,
          tester.element(find.byType(Image).first),
        );
      }
    });
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  }

  void expectDecoded(WidgetTester tester) {
    final raw = tester.widget<RawImage>(find.byType(RawImage).first);
    expect(raw.image, isNotNull);
    expect(raw.image!.width, 120);
    expect(raw.image!.height, 60);
    expect(find.text('图片加载失败'), findsNothing);
  }

  testWidgets(
    'AI Markdown mixes decoded image and surrounding prose, opens fullscreen',
    (tester) async {
      await render(
        tester,
        MathMarkdownView(
          content: '这是配图前的解释。\n\n![结构示意图]($dataUrl)\n\n这是配图后的说明。',
        ),
      );
      expectDecoded(tester);
      expect(find.text('结构示意图'), findsOneWidget);
      expect(find.textContaining('配图后的说明', findRichText: true), findsOneWidget);
      await tester.runAsync(() async {
        final screenshot =
            await (boundary.currentContext!.findRenderObject()
                    as RenderRepaintBoundary)
                .toImage();
        final bytes = await screenshot.toByteData(
          format: ui.ImageByteFormat.png,
        );
        final output = File('../../qa/chat-images-20261010/markdown.png');
        await output.parent.create(recursive: true);
        await output.writeAsBytes(bytes!.buffer.asUint8List());
        screenshot.dispose();
      });
      await tester.runAsync(() async {
        await tester.tap(find.byIcon(Icons.open_in_full));
        await tester.pumpAndSettle();
        for (final image in tester.widgetList<Image>(find.byType(Image))) {
          await precacheImage(
            image.image,
            tester.element(find.byType(Image).first),
          );
        }
      });
      await tester.pumpAndSettle();
      expect(find.text('查看图片'), findsOneWidget);
      expect(find.byType(InteractiveViewer), findsOneWidget);
      expectDecoded(tester);
    },
  );

  testWidgets('image message displays local files, including user bubble', (
    tester,
  ) async {
    final directory = Directory.systemTemp.createTempSync('chat-image-');
    addTearDown(() => directory.deleteSync(recursive: true));
    final file = File('${directory.path}/image with spaces.png');
    file.writeAsBytesSync(png);
    for (final source in [file.uri.toString(), file.path]) {
      await render(
        tester,
        EnhancedImageView(
          part: MessagePartDto(type: 'image', url: source),
          messageId: 'image',
        ),
      );
      expectDecoded(tester);
    }
    await render(
      tester,
      MessageBubble(
        message: MessageDto(
          id: 'user',
          nodeId: 'node',
          role: 'USER',
          createdAt: '',
          revision: 0,
          parts: [MessagePartDto(type: 'image', url: file.uri.toString())],
        ),
        isUser: true,
      ),
    );
    expectDecoded(tester);
  });

  testWidgets(
    'interactive image loads real image bytes from network response',
    (tester) async {
      debugNetworkImageHttpClientProvider = () => ImageClient(png);
      addTearDown(() => debugNetworkImageHttpClientProvider = null);
      final definition = SurfaceDefinition.parse({
        'version': 3,
        'title': '带图解释',
        'components': [
          {
            'id': 'figure',
            'type': 'image',
            'url': 'https://example.org/figure.png',
            'caption': '图与说明一同展示',
          },
        ],
      });
      expect(definition.defaults, isEmpty);
      await render(
        tester,
        InteractiveSurfaceView(
          definition: definition,
          save: (_, _, _) async {},
        ),
      );
      expectDecoded(tester);
      expect(find.text('图与说明一同展示'), findsOneWidget);
      debugNetworkImageHttpClientProvider = null;
    },
  );

  testWidgets(
    'invalid address and malformed image data show recoverable failure',
    (tester) async {
      for (final source in [
        'javascript:alert(1)',
        'data:image/png;base64,broken',
      ]) {
        await tester.pumpWidget(MaterialApp(home: ChatImage(source: source)));
        await tester.pumpAndSettle();
        expect(find.text('图片加载失败'), findsOneWidget);
        expect(find.text('重新加载'), findsOneWidget);
        expect(tester.takeException(), isNull);
      }
    },
  );

  test(
    'interactive image schema rejects missing and unsupported addresses',
    () {
      for (final url in [
        null,
        'javascript:alert(1)',
        'file:///private/photo.png',
      ]) {
        expect(
          () => SurfaceDefinition.parse({
            'version': 3,
            'title': '图片',
            'components': [
              {'id': 'picture', 'type': 'image', 'url': url},
            ],
          }),
          throwsFormatException,
        );
      }
    },
  );
}
