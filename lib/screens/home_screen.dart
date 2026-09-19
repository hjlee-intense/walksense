import 'dart:async';

import 'package:flutter/material.dart';
import 'package:walksense/services/permission_service.dart';
import 'package:walksense/services/walking_service.dart';
import 'package:walksense/widgets/walking_service_controls.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  final PermissionService _permissionService = PermissionService();
  final WalkingService _walkingService = WalkingService();

  /// 보행 상태 구독.
  StreamSubscription<WalkingStatus>? _walkingSubscription;

  /// 신체 활동 권한 상태.
  AppPermissionStatus? _permissionStatus;

  /// 다른 앱 위에 경고를 표시하기 위한 오버레이 권한 상태.
  AppPermissionStatus? _overlayPermissionStatus;

  /// 최근 보행 상태.
  WalkingStatus? _walkingStatus;

  /// 권한 확인·요청 진행 여부.
  bool _isRequestingPermission = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _isRequestingPermission = true;
    unawaited(_checkInitialPermission());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed &&
        _permissionStatus == AppPermissionStatus.granted) {
      unawaited(_refreshOverlayPermission());
    }
  }

  /// 오버레이 권한 설정 화면에서 돌아왔을 때 최신 상태를 다시 확인한다.
  Future<void> _refreshOverlayPermission() async {
    final status = await _permissionService.checkOverlayPermission();
    if (mounted) {
      setState(() => _overlayPermissionStatus = status);
    }
  }

  /// 화면 시작 시 기존 권한을 확인하고, 허용돼 있으면 보행 감지를 시작한다.
  Future<void> _checkInitialPermission() async {
    try {
      final status = await _permissionService.checkActivityRecognition();
      if (!mounted) return;

      setState(() {
        _permissionStatus = status;
      });

      if (status == AppPermissionStatus.granted) {
        await _refreshOverlayPermission();
        _startWalkingDetection();
      }
    } catch (error) {
      debugPrint('초기 신체 활동 권한 확인 실패: $error');
    } finally {
      if (mounted) {
        setState(() {
          _isRequestingPermission = false;
        });
      }
    }
  }

  /// 신체 활동 권한을 확인·요청하고, 허용되면 보행 감지를 시작한다.
  Future<void> _requestActivityRecognitionPermission() async {
    setState(() {
      _isRequestingPermission = true;
    });

    var status = await _permissionService.checkActivityRecognition();

    if (status == AppPermissionStatus.denied) {
      status = await _permissionService.requestActivityRecognition();
    }

    if (!mounted) return;

    setState(() {
      _permissionStatus = status;
      _isRequestingPermission = false;
    });

    if (status == AppPermissionStatus.granted) {
      await _refreshOverlayPermission();
      if (_overlayPermissionStatus != AppPermissionStatus.granted) {
        await _permissionService.requestOverlayPermission();
        if (mounted) _showOverlaySettingsSnackBar();
      }
      _startWalkingDetection();
    }

    if (status == AppPermissionStatus.permanentlyDenied) {
      _showSettingsSnackBar();
    }
  }

  /// 보행 상태 스트림 구독 및 감지 시작.
  ///
  /// 걷기 경고 자체는 [WalkingDetectionService]가 다른 앱 위에도 보이는
  /// 오버레이로 직접 띄우므로, 여기서는 상태 표시용으로만 스트림을 구독한다.
  void _startWalkingDetection() {
    _walkingSubscription ??= _walkingService.statusStream.listen(
      _handleWalkingStatus,
    );

    unawaited(_walkingService.start());
  }

  void _handleWalkingStatus(WalkingStatus status) {
    if (!mounted) return;
    setState(() {
      _walkingStatus = status;
    });
  }

  void _showSettingsSnackBar() {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: const Text('신체 활동 권한을 설정에서 허용해 주세요.'),
        action: SnackBarAction(
          label: '설정 열기',
          onPressed: _permissionService.openSettings,
        ),
      ),
    );
  }

  void _showOverlaySettingsSnackBar() {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: const Text('다른 앱 위에도 경고를 표시하려면 "다른 앱 위에 표시" 권한을 허용해 주세요.'),
        action: SnackBarAction(
          label: '설정 열기',
          onPressed: _permissionService.requestOverlayPermission,
        ),
      ),
    );
  }

  String get _permissionMessage {
    return switch (_permissionStatus) {
      AppPermissionStatus.granted => '보행 감지를 시작할 준비가 되었습니다.',
      AppPermissionStatus.denied => '보행 감지를 사용하려면 신체 활동 권한이 필요합니다.',
      AppPermissionStatus.permanentlyDenied => '설정에서 신체 활동 권한을 허용해 주세요.',
      AppPermissionStatus.restricted => '기기 설정에 의해 신체 활동 권한이 제한되어 있습니다.',
      AppPermissionStatus.limited => '신체 활동 권한이 일부만 허용되어 있습니다.',
      AppPermissionStatus.provisional => '신체 활동 권한이 임시로 허용되어 있습니다.',
      null => '권한을 허용하면 보행 상태를 감지할 수 있습니다.',
    };
  }

  /// 보행 상태에 따른 안내 문구.
  String get _walkingMessage {
    return switch (_walkingStatus) {
      WalkingStatus.walking => '현재 걷는 중입니다.',
      WalkingStatus.stopped => '현재 멈춰 있습니다.',
      WalkingStatus.unknown => '보행 상태를 확인하고 있습니다.',
      WalkingStatus.unavailable => '보행 센서를 사용할 수 없습니다.',
      null => '보행 상태를 기다리고 있습니다.',
    };
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    unawaited(_walkingSubscription?.cancel());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Walksense')),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.directions_walk, size: 80),
              const SizedBox(height: 24),
              Text(_permissionMessage, textAlign: TextAlign.center),
              if (_permissionStatus == AppPermissionStatus.granted) ...[
                const SizedBox(height: 12),
                Text(_walkingMessage, textAlign: TextAlign.center),
                if (_overlayPermissionStatus != AppPermissionStatus.granted) ...[
                  const SizedBox(height: 12),
                  Text(
                    '다른 앱을 쓰는 중에도 경고를 표시하려면 "다른 앱 위에 표시" 권한이 필요합니다.',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                  TextButton(
                    onPressed: () async {
                      await _permissionService.requestOverlayPermission();
                    },
                    child: const Text('오버레이 권한 설정 열기'),
                  ),
                ],
              ],
              const SizedBox(height: 24),
              FilledButton(
                onPressed:
                    _isRequestingPermission ||
                        _permissionStatus == AppPermissionStatus.granted
                    ? null
                    : _requestActivityRecognitionPermission,
                child: Text(
                  _isRequestingPermission
                      ? '권한 확인 중...'
                      : _permissionStatus == AppPermissionStatus.granted
                      ? '보행 감지 중'
                      : '보행 감지 준비',
                ),
              ),
              const SizedBox(height: 24),
              const WalkingServiceControls(),
            ],
          ),
        ),
      ),
    );
  }
}
