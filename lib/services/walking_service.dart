import 'package:flutter/services.dart';

/// Android 서비스가 판단하는 보행 상태.
enum WalkingStatus { walking, stopped, unknown, unavailable }

/// Android 포그라운드 서비스 제어 및 보행 상태 수신.
class WalkingService {
  static const _channel = MethodChannel('walksense/walking_service');
  static const _events = EventChannel('walksense/walking_service_state');
  static const _statusEvents = EventChannel('walksense/walking_status');

  Stream<bool> get runningStream =>
      _events.receiveBroadcastStream().map((event) => event as bool);

  /// Android 서비스가 판단한 보행 상태 스트림.
  Stream<WalkingStatus> get statusStream => _statusEvents
      .receiveBroadcastStream()
      .map((event) => _toWalkingStatus(event as String));

  Future<void> start() => _channel.invokeMethod<void>('start');
  Future<void> stop() => _channel.invokeMethod<void>('stop');
  Future<bool> isRunning() async =>
      await _channel.invokeMethod<bool>('isRunning') ?? false;

  WalkingStatus _toWalkingStatus(String status) {
    return switch (status) {
      'walking' => WalkingStatus.walking,
      'stopped' => WalkingStatus.stopped,
      'unavailable' => WalkingStatus.unavailable,
      _ => WalkingStatus.unknown,
    };
  }
}
