import 'package:permission_handler/permission_handler.dart';

/// Walksense 앱에서 사용하는 권한 상태.
enum AppPermissionStatus {
  granted,
  denied,
  permanentlyDenied,
  restricted,
  limited,
  provisional,
}

class PermissionService {
  /// 신체 활동 권한의 현재 상태 확인.
  Future<AppPermissionStatus> checkActivityRecognition() async {
    final status = await Permission.activityRecognition.status;
    return _toAppPermissionStatus(status);
  }

  /// 보행 감지에 필요한 신체 활동 권한 요청.
  Future<AppPermissionStatus> requestActivityRecognition() async {
    final status = await Permission.activityRecognition.request();
    return _toAppPermissionStatus(status);
  }

  /// 권한 영구 거절 시 앱 설정 화면 열기.
  Future<bool> openSettings() {
    return openAppSettings();
  }

  AppPermissionStatus _toAppPermissionStatus(PermissionStatus status) {
    if (status.isGranted) {
      return AppPermissionStatus.granted;
    }
    if (status.isPermanentlyDenied) {
      return AppPermissionStatus.permanentlyDenied;
    }
    if (status.isRestricted) {
      return AppPermissionStatus.restricted;
    }
    if (status.isLimited) {
      return AppPermissionStatus.limited;
    }
    if (status.isProvisional) {
      return AppPermissionStatus.provisional;
    }
    return AppPermissionStatus.denied;
  }
}
