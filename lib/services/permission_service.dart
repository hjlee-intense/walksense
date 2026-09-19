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
  /// 서비스 실행 알림 표시 권한. 거부해도 서비스 시작 자체는 가능하다.
  Future<AppPermissionStatus> requestNotifications() async {
    return _toAppPermissionStatus(await Permission.notification.request());
  }

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

  /// 다른 앱 위에 경고 오버레이를 표시하기 위한 권한의 현재 상태 확인.
  Future<AppPermissionStatus> checkOverlayPermission() async {
    final status = await Permission.systemAlertWindow.status;
    return _toAppPermissionStatus(status);
  }

  /// 오버레이 권한 설정 화면 열기.
  ///
  /// 이 권한은 런타임 다이얼로그가 아니라 시스템 설정 화면으로 이동하는 방식이라,
  /// 반환값은 사용자가 실제로 허용했는지를 보장하지 않는다. 설정 화면에서 돌아온 뒤
  /// [checkOverlayPermission]으로 다시 확인해야 한다.
  Future<void> requestOverlayPermission() async {
    await Permission.systemAlertWindow.request();
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
