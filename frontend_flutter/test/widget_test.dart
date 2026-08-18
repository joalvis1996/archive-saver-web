import 'package:flutter_test/flutter_test.dart';
import 'package:frontend_flutter/offline_archive_manager.dart';

void main() {
  group('OfflineArchiveManager Tests', () {
    test('formatBytes formats file sizes correctly', () {
      expect(OfflineArchiveManager.formatBytes(0), '0 B');
      expect(OfflineArchiveManager.formatBytes(500), '500.0 B');
      expect(OfflineArchiveManager.formatBytes(1024), '1.0 KB');
      expect(OfflineArchiveManager.formatBytes(1024 * 1024 * 5), '5.0 MB');
      expect(OfflineArchiveManager.formatBytes(1024 * 1024 * 1024 * 2), '2.0 GB');
    });
  });
}
