import 'dart:async';

import 'package:flutter/services.dart';

import 'study_host_api.g.dart';

/// Client singleton interfacing with Pigeon typed HostApi and EventChannel.
class StudyBridgeClient {
  static final StudyBridgeClient instance = StudyBridgeClient._();
  StudyBridgeClient._();

  final StudyHostApi api = StudyHostApi();
  static const EventChannel _eventChannel = EventChannel(
    'me.rerere.rikkahub/study_events',
  );

  Stream<Map<String, dynamic>>? _eventStream;

  Stream<Map<String, dynamic>> get eventStream {
    _eventStream ??= _eventChannel.receiveBroadcastStream().map(
      (event) => Map<String, dynamic>.from(event as Map),
    );
    return _eventStream!;
  }
}
