import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:walksense/services/permission_service.dart';
import 'package:walksense/services/walking_service.dart';

/// 현재 단계에서는 센서 감지와 별도로 Android 서비스 실행을 제어한다.
class WalkingServiceControls extends StatefulWidget {
  const WalkingServiceControls({super.key});

  @override
  State<WalkingServiceControls> createState() => _WalkingServiceControlsState();
}

class _WalkingServiceControlsState extends State<WalkingServiceControls>
    with WidgetsBindingObserver {
  final _service = WalkingService();
  final _permissions = PermissionService();
  StreamSubscription<bool>? _subscription;
  bool _running = false;
  bool _busy = false;
  String? _message;

  bool get _supported =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  @override
  void initState() {
    super.initState();
    if (!_supported) return;
    WidgetsBinding.instance.addObserver(this);
    _subscription = _service.runningStream.listen(
      (running) {
        if (mounted) setState(() => _running = running);
      },
      onError: (Object error) {
        if (mounted) {
          setState(() => _message = '서비스 상태를 확인하지 못했습니다. 다시 시도해 주세요.');
        }
      },
    );
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) unawaited(_refresh());
  }

  Future<void> _refresh() async {
    try {
      final running = await _service.isRunning();
      if (mounted) setState(() => _running = running);
    } catch (_) {
      if (mounted) setState(() => _message = '서비스 상태를 확인하지 못했습니다.');
    }
  }

  Future<void> _toggle() async {
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      if (_running) {
        await _service.stop();
      } else {
        var permission = await _permissions.checkActivityRecognition();
        if (permission == AppPermissionStatus.denied) {
          permission = await _permissions.requestActivityRecognition();
        }
        if (!mounted) return;
        if (permission != AppPermissionStatus.granted) {
          setState(() => _message = '서비스를 시작하려면 신체 활동 권한을 허용해 주세요.');
          return;
        }
        final notifications = await _permissions.requestNotifications();
        if (!mounted) return;
        if (notifications != AppPermissionStatus.granted) {
          setState(() => _message = '알림이 꺼져 있어 실행 알림이 표시되지 않을 수 있습니다.');
        }
        await _service.start();
      }
      // 실행 여부는 요청 성공 여부가 아니라 네이티브 서비스 이벤트로 갱신한다.
    } catch (_) {
      if (mounted) {
        setState(() => _message = '서비스를 제어하지 못했습니다. 다시 시도해 주세요.');
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    unawaited(_subscription?.cancel());
    // 화면이 닫혀도 Android 서비스는 유지한다.
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!_supported) return const SizedBox.shrink();
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(_running ? '백그라운드 서비스 실행 중' : '백그라운드 서비스 중지됨'),
        const SizedBox(height: 8),
        FilledButton.tonal(
          onPressed: _busy ? null : _toggle,
          child: Text(
            _busy
                ? '처리 중...'
                : _running
                ? '서비스 중지'
                : '서비스 시작',
          ),
        ),
        if (_message != null) Text(_message!, textAlign: TextAlign.center),
      ],
    );
  }
}
