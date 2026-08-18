import 'dart:async';
import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'offline_archive_manager.dart';

class InAppArchiveViewer extends StatefulWidget {
  const InAppArchiveViewer({
    super.key,
    required this.archiveId,
    required this.title,
    required this.originalUrl,
    required this.backendUrl,
  });

  final String archiveId;
  final String title;
  final String originalUrl;
  final String backendUrl;

  @override
  State<InAppArchiveViewer> createState() => _InAppArchiveViewerState();
}

class _InAppArchiveViewerState extends State<InAppArchiveViewer> {
  late final WebViewController _controller;
  bool _isLoading = true;
  bool _isOfflineLoaded = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _initWebView();
  }

  Future<void> _initWebView() async {
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageStarted: (_) {
            if (mounted) setState(() => _isLoading = true);
          },
          onPageFinished: (_) {
            if (mounted) setState(() => _isLoading = false);
          },
          onWebResourceError: (error) {
            // 로컬 파일 로드 중 사소한 리소스 누락은 무시
            if (!_isOfflineLoaded && error.isForMainFrame == true) {
              if (mounted) {
                setState(() {
                  _isLoading = false;
                  _errorMessage = '페이지 로드 실패: ${error.description}';
                });
              }
            }
          },
        ),
      );

    // 1. 로컬 캐시 확인
    final cachedFile = await OfflineArchiveManager.instance.getCachedHtmlFile(
      widget.archiveId,
    );

    if (cachedFile != null && mounted) {
      // 로컬 파일에서 즉시 로드
      _isOfflineLoaded = true;
      try {
        final html = await cachedFile.readAsString();
        await _controller.loadHtmlString(
          html,
          baseUrl: Uri.file(cachedFile.parent.path).toString(),
        );
      } catch (_) {
        await _controller.loadFile(cachedFile.path);
      }
      if (mounted) setState(() => _isLoading = false);
      return;
    }

    // 2. 캐시가 없는 경우 원격 서버에서 로드
    final remoteUrl = '${widget.backendUrl}/archive/${widget.archiveId}';
    try {
      await _controller.loadRequest(Uri.parse(remoteUrl));
      // 다음 번 빠른 열람을 위해 백그라운드에서 자동 캐싱
      unawaited(
        OfflineArchiveManager.instance.cacheHtmlArchive(
          archiveId: widget.archiveId,
          backendUrl: widget.backendUrl,
        ),
      );
    } catch (e) {
      if (mounted) {
        setState(() {
          _isLoading = false;
          _errorMessage = '페이지를 불러올 수 없습니다: $e';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF111827),
      body: SafeArea(
        child: Column(
          children: [
            // 미니멀 상단 헤더 (주소창 없이 제목 + 오프라인 상태 + 닫기 버튼만 제공)
            Container(
              height: 48,
              padding: const EdgeInsets.symmetric(horizontal: 12),
              decoration: const BoxDecoration(
                color: Color(0xFF1F2937),
                border: Border(
                  bottom: BorderSide(color: Color(0xFF374151), width: 1),
                ),
              ),
              child: Row(
                children: [
                  if (_isOfflineLoaded) ...[
                    const Icon(
                      Icons.offline_pin,
                      size: 16,
                      color: Colors.greenAccent,
                    ),
                    const SizedBox(width: 6),
                  ],
                  Expanded(
                    child: Text(
                      widget.title.isNotEmpty ? widget.title : '아카이브 뷰어',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: Colors.white,
                      ),
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close, color: Colors.white70),
                    iconSize: 22,
                    padding: EdgeInsets.zero,
                    constraints: const BoxConstraints(minWidth: 36, minHeight: 36),
                    tooltip: '닫기',
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
            ),

            // 전체 화면 컨텐츠 뷰어 영역 (하단 바 없음)
            Expanded(
              child: Stack(
                children: [
                  WebViewWidget(controller: _controller),
                  if (_isLoading)
                    Container(
                      color: const Color(0xFF111827),
                      child: const Center(
                        child: CircularProgressIndicator(
                          color: Color(0xFF0A84FF),
                        ),
                      ),
                    ),
                  if (_errorMessage != null)
                    Container(
                      color: const Color(0xFF111827),
                      padding: const EdgeInsets.all(24),
                      child: Center(
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Icon(
                              Icons.error_outline,
                              color: Colors.redAccent,
                              size: 48,
                            ),
                            const SizedBox(height: 16),
                            Text(
                              _errorMessage!,
                              textAlign: TextAlign.center,
                              style: const TextStyle(color: Colors.white70),
                            ),
                            const SizedBox(height: 20),
                            Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                OutlinedButton(
                                  onPressed: () => Navigator.of(context).pop(),
                                  child: const Text('닫기'),
                                ),
                                const SizedBox(width: 12),
                                FilledButton(
                                  onPressed: () {
                                    setState(() {
                                      _errorMessage = null;
                                      _isLoading = true;
                                    });
                                    _initWebView();
                                  },
                                  child: const Text('다시 시도'),
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
