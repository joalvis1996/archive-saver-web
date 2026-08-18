import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';

class OfflineArchiveManager {
  OfflineArchiveManager._();
  static final OfflineArchiveManager instance = OfflineArchiveManager._();

  static const String _offlineDirName = 'offline_archives';
  static const String _indexFileName = 'index.html';
  static const String _imageFileName = 'image.png';

  /// 오프라인 아카이브 루트 디렉터리를 반환합니다.
  Future<Directory> _getRootDir() async {
    final appDir = await getApplicationDocumentsDirectory();
    final rootDir = Directory('${appDir.path}/$_offlineDirName');
    if (!await rootDir.exists()) {
      await rootDir.create(recursive: true);
    }
    return rootDir;
  }

  /// 특정 아카이브의 전용 디렉터리를 반환합니다.
  Future<Directory> _getArchiveDir(String archiveId) async {
    final rootDir = await _getRootDir();
    final safeId = archiveId.replaceAll(RegExp(r'[^A-Za-z0-9._-]'), '_');
    final archiveDir = Directory('${rootDir.path}/$safeId');
    if (!await archiveDir.exists()) {
      await archiveDir.create(recursive: true);
    }
    return archiveDir;
  }

  /// 아카이브가 로컬에 캐시되어 있는지 여부를 확인합니다.
  Future<bool> isCached(String archiveId) async {
    try {
      final safeId = archiveId.replaceAll(RegExp(r'[^A-Za-z0-9._-]'), '_');
      final rootDir = await _getRootDir();
      final archiveDir = Directory('${rootDir.path}/$safeId');
      if (!await archiveDir.exists()) return false;

      final indexFile = File('${archiveDir.path}/$_indexFileName');
      final imageFile = File('${archiveDir.path}/$_imageFileName');

      if (await indexFile.exists() && (await indexFile.length()) > 0) {
        return true;
      }
      if (await imageFile.exists() && (await imageFile.length()) > 0) {
        return true;
      }
      return false;
    } catch (_) {
      return false;
    }
  }

  /// 캐시된 모든 아카이브 ID 집합을 반환합니다. (대시보드 UI 고속 렌더링용)
  Future<Set<String>> getAllCachedArchiveIds() async {
    final cachedIds = <String>{};
    try {
      final rootDir = await _getRootDir();
      if (!await rootDir.exists()) return cachedIds;
      final entities = rootDir.listSync();
      for (final entity in entities) {
        if (entity is Directory) {
          final id = entity.uri.pathSegments
              .where((segment) => segment.isNotEmpty)
              .last;
          final indexFile = File('${entity.path}/$_indexFileName');
          final imageFile = File('${entity.path}/$_imageFileName');
          if ((indexFile.existsSync() && indexFile.lengthSync() > 0) ||
              (imageFile.existsSync() && imageFile.lengthSync() > 0)) {
            cachedIds.add(id);
          }
        }
      }
    } catch (e) {
      debugPrint('캐시된 아카이브 목록 조회 실패: $e');
    }
    return cachedIds;
  }

  /// 캐시된 HTML 파일 객체를 반환합니다. (없으면 null)
  Future<File?> getCachedHtmlFile(String archiveId) async {
    try {
      final archiveDir = await _getArchiveDir(archiveId);
      final indexFile = File('${archiveDir.path}/$_indexFileName');
      if (await indexFile.exists() && (await indexFile.length()) > 0) {
        return indexFile;
      }
    } catch (e) {
      debugPrint('로컬 HTML 파일 조회 실패: $e');
    }
    return null;
  }

  /// 캐시된 스크린샷 이미지 바이너리를 반환합니다. (없으면 null)
  Future<Uint8List?> getCachedImageBytes(String archiveId) async {
    try {
      final archiveDir = await _getArchiveDir(archiveId);
      final imageFile = File('${archiveDir.path}/$_imageFileName');
      if (await imageFile.exists() && (await imageFile.length()) > 0) {
        return await imageFile.readAsBytes();
      }
    } catch (e) {
      debugPrint('로컬 이미지 파일 조회 실패: $e');
    }
    return null;
  }

  /// HTML 아카이브를 로컬에 다운로드하여 저장합니다.
  Future<bool> cacheHtmlArchive({
    required String archiveId,
    required String backendUrl,
  }) async {
    try {
      final archiveDir = await _getArchiveDir(archiveId);
      final indexFile = File('${archiveDir.path}/$_indexFileName');

      final viewerUrl = '$backendUrl/archive/$archiveId';
      final response = await http.get(
        Uri.parse(viewerUrl),
        headers: {'Bypass-Tunnel-Reminder': 'true'},
      ).timeout(const Duration(seconds: 30));

      if (response.statusCode != 200) {
        debugPrint('HTML 아카이브 다운로드 실패 (상태 코드: ${response.statusCode})');
        return false;
      }

      var html = response.body;
      if (html.isEmpty) return false;

      // 오프라인 상대 경로 및 뷰포트 메타 태그 최적화
      if (!html.toLowerCase().contains('<base')) {
        if (html.toLowerCase().contains('<head>')) {
          html = html.replaceFirst(
            RegExp(r'<head>', caseSensitive: false),
            '<head><base href="./">',
          );
        } else {
          html = '<base href="./">$html';
        }
      }

      await indexFile.writeAsString(html, flush: true);
      return true;
    } catch (e) {
      debugPrint('HTML 아카이브 캐싱 실패 ($archiveId): $e');
      return false;
    }
  }

  /// 스크린샷 아카이브를 로컬에 다운로드하여 저장합니다.
  Future<bool> cacheScreenshotArchive({
    required String archiveId,
    required String backendUrl,
    required String accessToken,
  }) async {
    try {
      final archiveDir = await _getArchiveDir(archiveId);
      final imageFile = File('${archiveDir.path}/$_imageFileName');

      final response = await http.get(
        Uri.parse('$backendUrl/api/archive/$archiveId/file'),
        headers: {
          'Authorization': 'Bearer $accessToken',
          'Bypass-Tunnel-Reminder': 'true',
        },
      ).timeout(const Duration(seconds: 30));

      if (response.statusCode != 200) {
        debugPrint('스크린샷 다운로드 실패 (상태 코드: ${response.statusCode})');
        return false;
      }

      await imageFile.writeAsBytes(response.bodyBytes, flush: true);
      return true;
    } catch (e) {
      debugPrint('스크린샷 캐싱 실패 ($archiveId): $e');
      return false;
    }
  }

  /// 특정 아카이브의 로컬 캐시를 삭제합니다.
  Future<void> deleteArchive(String archiveId) async {
    try {
      final safeId = archiveId.replaceAll(RegExp(r'[^A-Za-z0-9._-]'), '_');
      final rootDir = await _getRootDir();
      final archiveDir = Directory('${rootDir.path}/$safeId');
      if (await archiveDir.exists()) {
        await archiveDir.delete(recursive: true);
      }
    } catch (e) {
      debugPrint('아카이브 캐시 삭제 실패 ($archiveId): $e');
    }
  }

  /// 전체 오프라인 캐시를 비웁니다.
  Future<void> clearAllArchives() async {
    try {
      final rootDir = await _getRootDir();
      if (await rootDir.exists()) {
        await rootDir.delete(recursive: true);
      }
    } catch (e) {
      debugPrint('전체 아카이브 캐시 삭제 실패: $e');
    }
  }

  /// 전체 오프라인 캐시 용량(바이트)을 계산합니다.
  Future<int> getOfflineStorageSizeBytes() async {
    int totalBytes = 0;
    try {
      final rootDir = await _getRootDir();
      if (!await rootDir.exists()) return 0;
      final entities = rootDir.listSync(recursive: true);
      for (final entity in entities) {
        if (entity is File) {
          totalBytes += await entity.length();
        }
      }
    } catch (e) {
      debugPrint('오프라인 용량 계산 실패: $e');
    }
    return totalBytes;
  }

  /// 바이트 단위를 읽기 좋은 문자열(KB, MB)로 변환합니다.
  static String formatBytes(int bytes) {
    if (bytes <= 0) return '0 B';
    const suffixes = ['B', 'KB', 'MB', 'GB'];
    var i = 0;
    double size = bytes.toDouble();
    while (size >= 1024 && i < suffixes.length - 1) {
      size /= 1024;
      i++;
    }
    return '${size.toStringAsFixed(1)} ${suffixes[i]}';
  }

  /// 모든 아카이브를 순차적으로 오프라인 캐싱합니다.
  Future<void> syncAllArchives({
    required List<dynamic> archives,
    required String backendUrl,
    required String? accessToken,
    required void Function(int current, int total, String title) onProgress,
  }) async {
    final total = archives.length;
    var current = 0;

    for (final item in archives) {
      current++;
      final id = item['id']?.toString() ?? '';
      final title = item['title']?.toString() ?? '제목 없음';
      final url = item['url']?.toString() ?? '';
      final isScreenshot = url.startsWith('screenshot://');

      if (id.isEmpty) continue;

      // 이미 캐시되어 있으면 진행률만 갱신하고 건너뜁니다.
      if (await isCached(id)) {
        onProgress(current, total, '$title (이미 보관됨)');
        continue;
      }

      onProgress(current, total, '$title 다운로드 중...');

      if (isScreenshot) {
        if (accessToken != null && accessToken.isNotEmpty) {
          await cacheScreenshotArchive(
            archiveId: id,
            backendUrl: backendUrl,
            accessToken: accessToken,
          );
        }
      } else {
        await cacheHtmlArchive(
          archiveId: id,
          backendUrl: backendUrl,
        );
      }

      // 서버 과부하 방지를 위한 짧은 딜레이
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }

    onProgress(total, total, '동기화 완료');
  }
}
