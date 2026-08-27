import 'package:flutter/material.dart';
import 'package:walksense/services/permission_service.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final PermissionService _permissionService = PermissionService();

  AppPermissionStatus? _permissionStatus;
  bool _isRequestingPermission = false;

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

    if (status == AppPermissionStatus.permanentlyDenied) {
      _showSettingsSnackBar();
    }
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
              const SizedBox(height: 24),
              FilledButton(
                onPressed: _isRequestingPermission
                    ? null
                    : _requestActivityRecognitionPermission,
                child: Text(
                  _isRequestingPermission ? '권한 확인 중...' : '보행 감지 준비',
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
