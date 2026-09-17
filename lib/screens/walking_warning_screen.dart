import 'package:flutter/material.dart';

/// 보행 중에 앱 콘텐츠를 가리는 전체 화면 안내.
class WalkingWarningScreen extends StatefulWidget {
  const WalkingWarningScreen({super.key});

  @override
  State<WalkingWarningScreen> createState() => _WalkingWarningScreenState();
}

class _WalkingWarningScreenState extends State<WalkingWarningScreen>
    with SingleTickerProviderStateMixin {
  late final AnimationController _pulseController;
  late final Animation<double> _pulseAnimation;

  @override
  void initState() {
    super.initState();
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1200),
    )..repeat(reverse: true);
    _pulseAnimation = Tween<double>(begin: 0.8, end: 1.2).animate(
      CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
    );
  }

  @override
  void dispose() {
    _pulseController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;

    return Scaffold(
      backgroundColor: colors.surface,
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            return SingleChildScrollView(
              child: ConstrainedBox(
                constraints: BoxConstraints(
                  minWidth: constraints.maxWidth,
                  minHeight: constraints.maxHeight,
                ),
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 32,
                    vertical: 48,
                  ),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      ScaleTransition(
                        scale: _pulseAnimation,
                        child: Container(
                          padding: const EdgeInsets.all(32),
                          decoration: BoxDecoration(
                            color: colors.errorContainer,
                            shape: BoxShape.circle,
                          ),
                          child: Icon(
                            Icons.directions_walk_rounded,
                            size: 88,
                            color: colors.onErrorContainer,
                          ),
                        ),
                      ),
                      const SizedBox(height: 60),
                      Semantics(
                        header: true,
                        liveRegion: true,
                        child: Text(
                          '걷는 중에는\n잠시 화면을 멀리해 주세요',
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.headlineMedium
                              ?.copyWith(fontWeight: FontWeight.bold),
                        ),
                      ),
                      const SizedBox(height: 20),
                      Text(
                        '고개를 들고 주변을 확인해 주세요.\n휴대폰은 안전한 곳에 멈춘 뒤 사용해 주세요.',
                        textAlign: TextAlign.center,
                        style: Theme.of(context).textTheme.bodyLarge
                            ?.copyWith(color: colors.onSurfaceVariant),
                      ),
                      const SizedBox(height: 48),
                      Text(
                        '멈춤이 감지되면 자동으로 돌아갑니다.',
                        textAlign: TextAlign.center,
                        style: Theme.of(context).textTheme.bodyMedium
                            ?.copyWith(color: colors.onSurfaceVariant),
                      ),
                    ],
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}
