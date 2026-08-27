import 'package:flutter/material.dart';
import 'package:walksense/screens/home_screen.dart';

void main() {
  runApp(const WalksenseApp());
}

class WalksenseApp extends StatelessWidget {
  const WalksenseApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Walksense',
      debugShowCheckedModeBanner: false,
      home: const HomeScreen(),
    );
  }
}
