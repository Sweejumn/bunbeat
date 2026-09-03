import 'package:flutter_test/flutter_test.dart';
import 'package:run_bpm_android/services/library_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('targetBpm 设置后能持久化并恢复', () async {
    SharedPreferences.setMockInitialValues({});
    final lib = LibraryService();
    expect(lib.targetBpm, 155); // 默认
    // 模拟用户在推荐页设置目标 BPM
    lib.targetBpm = 178;
    expect(lib.targetBpm, 178);
    // 让异步写盘完成
    await Future<void>.delayed(const Duration(milliseconds: 50));

    // 模拟重开 app：新实例恢复
    final lib2 = LibraryService();
    expect(lib2.targetBpm, 155); // 尚未加载
    await lib2.loadTargetBpm();
    expect(lib2.targetBpm, 178);
  });

  test('未保存过 targetBpm 时保持默认', () async {
    SharedPreferences.setMockInitialValues({});
    final lib = LibraryService();
    await lib.loadTargetBpm();
    expect(lib.targetBpm, 155);
  });
}
