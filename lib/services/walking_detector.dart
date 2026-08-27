import 'dart:async';
import 'package:pedometer/pedometer.dart';

/// Walksense 앱에서 사용하는 보행 상태.
enum WalkingStatus { walking, stopped, unknown, unavailable }

class WalkingDetector {
  final StreamController<WalkingStatus> _statusController =
      StreamController<WalkingStatus>.broadcast();

  StreamSubscription<PedestrianStatus>? _statusSubscription;

  /// 보행 상태 변경 스트림.
  Stream<WalkingStatus> get statusStream => _statusController.stream;

  /// 보행 상태 감지 시작.
  void start() {
    if (_statusSubscription != null) return;

    _statusSubscription = Pedometer.pedestrianStatusStream.listen(
      (event) {
        _statusController.add(_toWalkingStatus(event.status));
      },
      onError: (Object error) {
        _statusController.add(WalkingStatus.unavailable);
      },
    );
  }

  /// 보행 상태 감지 중지.
  Future<void> stop() async {
    await _statusSubscription?.cancel();
    _statusSubscription = null;
  }

  /// 보행 상태 감지 자원 해제.
  Future<void> dispose() async {
    await stop();
    await _statusController.close();
  }

  /// 플러그인 보행 상태를 Walksense 보행 상태로 변환.
  WalkingStatus _toWalkingStatus(String status) {
    return switch (status) {
      'walking' => WalkingStatus.walking,
      'stopped' => WalkingStatus.stopped,
      _ => WalkingStatus.unknown,
    };
  }
}
