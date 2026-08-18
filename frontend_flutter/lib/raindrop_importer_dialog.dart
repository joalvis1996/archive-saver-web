import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:supabase_flutter/supabase_flutter.dart';
import 'offline_archive_manager.dart';

class RaindropImporterDialog extends StatefulWidget {
  const RaindropImporterDialog({
    super.key,
    required this.backendUrl,
    required this.onImportCompleted,
  });

  final String backendUrl;
  final VoidCallback onImportCompleted;

  @override
  State<RaindropImporterDialog> createState() => _RaindropImporterDialogState();
}

class _RaindropImporterDialogState extends State<RaindropImporterDialog> {
  List<Map<String, dynamic>> _collections = [];
  Map<String, dynamic>? _selectedCollection;
  List<Map<String, dynamic>> _bookmarks = [];
  final Set<int> _selectedIndices = {};

  bool _isLoadingCollections = true;
  bool _isLoadingBookmarks = false;
  bool _isImporting = false;
  bool _isCancelled = false;

  int _importCurrent = 0;
  int _importTotal = 0;
  int _importSuccess = 0;
  int _importFailed = 0;
  String _importStatus = '';

  @override
  void initState() {
    super.initState();
    _fetchCollections();
  }

  Future<void> _fetchCollections() async {
    setState(() => _isLoadingCollections = true);
    try {
      final response = await http.get(
        Uri.parse('${widget.backendUrl}/api/collections'),
        headers: {'Bypass-Tunnel-Reminder': 'true'},
      );

      final list = <Map<String, dynamic>>[
        {'_id': 0, 'title': '전체 북마크 (All)'},
        {'_id': -1, 'title': '미분류 (Unsorted)'},
      ];

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        if (data is List) {
          for (final item in data) {
            if (item is Map<String, dynamic>) {
              list.add({
                '_id': item['_id'],
                'title': item['title'] ?? '이름 없음',
                'count': item['count'] ?? 0,
              });
            }
          }
        }
      }

      if (mounted) {
        setState(() {
          _collections = list;
          _selectedCollection = list.first;
          _isLoadingCollections = false;
        });
        _fetchBookmarks(list.first['_id']);
      }
    } catch (e) {
      if (mounted) {
        setState(() => _isLoadingCollections = false);
        _showError('컬렉션 목록을 불러오지 못했습니다: $e');
      }
    }
  }

  Future<void> _fetchBookmarks(dynamic collectionId) async {
    setState(() {
      _isLoadingBookmarks = true;
      _bookmarks = [];
      _selectedIndices.clear();
    });

    try {
      final response = await http.get(
        Uri.parse(
          '${widget.backendUrl}/api/raindrop/bookmarks?collectionId=$collectionId&perpage=50',
        ),
        headers: {'Bypass-Tunnel-Reminder': 'true'},
      );

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        final items = data['items'] as List<dynamic>? ?? [];
        final parsed = items.map((e) => Map<String, dynamic>.from(e)).toList();

        if (mounted) {
          setState(() {
            _bookmarks = parsed;
            _selectedIndices.addAll(List.generate(parsed.length, (i) => i));
            _isLoadingBookmarks = false;
          });
        }
      } else {
        throw Exception('상태 코드 ${response.statusCode}');
      }
    } catch (e) {
      if (mounted) {
        setState(() => _isLoadingBookmarks = false);
        _showError('북마크 목록을 불러오지 못했습니다: $e');
      }
    }
  }

  Future<void> _startImport() async {
    if (_selectedIndices.isEmpty) {
      _showError('가져올 북마크를 최소 1개 이상 선택해주세요.');
      return;
    }

    final session = Supabase.instance.client.auth.currentSession;
    if (session == null) {
      _showError('로그인이 필요합니다.');
      return;
    }

    final targets = _selectedIndices.map((i) => _bookmarks[i]).toList();

    setState(() {
      _isImporting = true;
      _isCancelled = false;
      _importCurrent = 0;
      _importTotal = targets.length;
      _importSuccess = 0;
      _importFailed = 0;
      _importStatus = '일괄 아카이빙 및 오프라인 저장 시작...';
    });

    for (var i = 0; i < targets.length; i++) {
      if (_isCancelled) break;

      final item = targets[i];
      final url = item['link']?.toString() ?? '';
      final title = item['title']?.toString() ?? '북마크';

      if (url.isEmpty || (!url.startsWith('http://') && !url.startsWith('https://'))) {
        _importFailed++;
        continue;
      }

      setState(() {
        _importCurrent = i + 1;
        _importStatus = '(${(i + 1)}/${targets.length}) \'$title\' 아카이빙 중...';
      });

      try {
        final response = await http.post(
          Uri.parse('${widget.backendUrl}/api/save-html'),
          headers: {
            'Content-Type': 'application/json',
            'Authorization': 'Bearer ${session.accessToken}',
            'Bypass-Tunnel-Reminder': 'true',
          },
          body: jsonEncode({'url': url, 'title': title}),
        ).timeout(const Duration(seconds: 45));

        if (response.statusCode == 200) {
          _importSuccess++;
          // 방금 생성된 아카이브 ID 추출 시도 (archiveUrl에서 추출)
          try {
            final resJson = jsonDecode(response.body);
            final archiveUrl = resJson['archiveUrl']?.toString() ?? '';
            final archiveId = archiveUrl.split('/').last.replaceAll('.html', '');
            if (archiveId.isNotEmpty) {
              // 즉시 로컬 오프라인 캐시에 저장
              await OfflineArchiveManager.instance.cacheHtmlArchive(
                archiveId: archiveId,
                backendUrl: widget.backendUrl,
              );
            }
          } catch (_) {}
        } else {
          _importFailed++;
        }
      } catch (e) {
        _importFailed++;
      }

      await Future<void>.delayed(const Duration(milliseconds: 150));
    }

    if (mounted) {
      setState(() {
        _isImporting = false;
        _importStatus = '완료: 성공 $_importSuccess건, 실패 $_importFailed건';
      });
      widget.onImportCompleted();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            'Raindrop 가져오기 완료: 성공 $_importSuccess건, 실패 $_importFailed건',
          ),
        ),
      );
      Navigator.of(context).pop();
    }
  }

  void _showError(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), backgroundColor: Colors.redAccent),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: const Color(0xFF1F2937),
      insetPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 24),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Container(
        padding: const EdgeInsets.all(20),
        constraints: const BoxConstraints(maxWidth: 550, maxHeight: 650),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // 상단 타이틀
            Row(
              children: [
                const Icon(Icons.cloud_download, color: Color(0xFF0A84FF), size: 24),
                const SizedBox(width: 10),
                const Expanded(
                  child: Text(
                    'Raindrop 북마크 가져오기 & 오프라인 저장',
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                    ),
                  ),
                ),
                if (!_isImporting)
                  IconButton(
                    icon: const Icon(Icons.close, color: Colors.white70),
                    padding: EdgeInsets.zero,
                    constraints: const BoxConstraints(),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
              ],
            ),
            const SizedBox(height: 16),

            // 컬렉션 선택기
            if (_isLoadingCollections)
              const Center(
                child: Padding(
                  padding: EdgeInsets.all(12),
                  child: CircularProgressIndicator(),
                ),
              )
            else
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                decoration: BoxDecoration(
                  color: const Color(0xFF111827),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: const Color(0xFF374151)),
                ),
                child: DropdownButtonHideUnderline(
                  child: DropdownButton<Map<String, dynamic>>(
                    value: _selectedCollection,
                    isExpanded: true,
                    dropdownColor: const Color(0xFF1F2937),
                    items: _collections.map((c) {
                      return DropdownMenuItem<Map<String, dynamic>>(
                        value: c,
                        child: Text(
                          c['title'] ?? '',
                          style: const TextStyle(color: Colors.white),
                        ),
                      );
                    }).toList(),
                    onChanged: _isImporting
                        ? null
                        : (val) {
                            if (val != null) {
                              setState(() => _selectedCollection = val);
                              _fetchBookmarks(val['_id']);
                            }
                          },
                  ),
                ),
              ),
            const SizedBox(height: 14),

            // 가져오기 진행 상태 (가져오기 실행 중일 때)
            if (_isImporting) ...[
              Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: const Color(0xFF111827),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Column(
                  children: [
                    LinearProgressIndicator(
                      value: _importTotal > 0 ? _importCurrent / _importTotal : null,
                      backgroundColor: Colors.grey.shade800,
                      valueColor: const AlwaysStoppedAnimation<Color>(
                        Color(0xFF0A84FF),
                      ),
                    ),
                    const SizedBox(height: 12),
                    Text(
                      _importStatus,
                      style: const TextStyle(color: Colors.white70, fontSize: 13),
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: 12),
                    OutlinedButton(
                      onPressed: () {
                        setState(() => _isCancelled = true);
                      },
                      child: const Text('중단하기'),
                    ),
                  ],
                ),
              ),
            ] else ...[
              // 북마크 목록 헤더 & 전체 선택 체크박스
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    '북마크 목록 (${_selectedIndices.length}/${_bookmarks.length}개 선택됨)',
                    style: const TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: Colors.grey,
                    ),
                  ),
                  if (_bookmarks.isNotEmpty)
                    TextButton(
                      onPressed: () {
                        setState(() {
                          if (_selectedIndices.length == _bookmarks.length) {
                            _selectedIndices.clear();
                          } else {
                            _selectedIndices.addAll(
                              List.generate(_bookmarks.length, (i) => i),
                            );
                          }
                        });
                      },
                      child: Text(
                        _selectedIndices.length == _bookmarks.length
                            ? '전체 해제'
                            : '전체 선택',
                        style: const TextStyle(fontSize: 12),
                      ),
                    ),
                ],
              ),
              const SizedBox(height: 6),

              // 북마크 리스트
              Expanded(
                child: _isLoadingBookmarks
                    ? const Center(child: CircularProgressIndicator())
                    : _bookmarks.isEmpty
                    ? const Center(
                        child: Text(
                          '해당 컬렉션에 북마크가 없습니다.',
                          style: TextStyle(color: Colors.grey),
                        ),
                      )
                    : Container(
                        decoration: BoxDecoration(
                          color: const Color(0xFF111827),
                          borderRadius: BorderRadius.circular(8),
                          border: Border.all(color: const Color(0xFF374151)),
                        ),
                        child: ListView.separated(
                          itemCount: _bookmarks.length,
                          separatorBuilder: (_, __) => const Divider(
                            height: 1,
                            color: Color(0xFF374151),
                          ),
                          itemBuilder: (context, index) {
                            final bm = _bookmarks[index];
                            final title = bm['title'] ?? '제목 없음';
                            final link = bm['link'] ?? '';
                            final isSelected = _selectedIndices.contains(index);

                            return CheckboxListTile(
                              value: isSelected,
                              dense: true,
                              activeColor: const Color(0xFF0A84FF),
                              onChanged: (checked) {
                                setState(() {
                                  if (checked == true) {
                                    _selectedIndices.add(index);
                                  } else {
                                    _selectedIndices.remove(index);
                                  }
                                });
                              },
                              title: Text(
                                title,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontSize: 13,
                                  color: Colors.white,
                                ),
                              ),
                              subtitle: Text(
                                link,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontSize: 11,
                                  color: Colors.grey,
                                ),
                              ),
                            );
                          },
                        ),
                      ),
              ),
              const SizedBox(height: 16),

              // 하단 가져오기 버튼
              FilledButton.icon(
                onPressed: _selectedIndices.isEmpty ? null : _startImport,
                icon: const Icon(Icons.download),
                label: Text(
                  '${_selectedIndices.length}개 북마크 가져와서 오프라인 저장',
                ),
                style: FilledButton.styleFrom(
                  backgroundColor: const Color(0xFF0A84FF),
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(vertical: 14),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
