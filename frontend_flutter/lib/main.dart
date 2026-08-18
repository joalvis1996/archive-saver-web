import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:supabase_flutter/supabase_flutter.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:http/http.dart' as http;
import 'package:receive_sharing_intent/receive_sharing_intent.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'in_app_archive_viewer.dart';
import 'offline_archive_manager.dart';
import 'raindrop_importer_dialog.dart';

// TODO: 본인의 Supabase 프로젝트 정보를 기입해 주세요.
const String supabaseUrl = 'https://rhimhzdszvayogorijkq.supabase.co';
const String supabaseAnonKey =
    'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InJoaW1oemRzenZheW9nb3JpamtxIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODIxODc1MDMsImV4cCI6MjA5Nzc2MzUwM30.3XUHVSLV7t2EI2AiePrMm_hkuXUcMvc-O8_6GUOa1W8';
const String passwordRecoveryRedirectUrl =
    'https://archive-saver-web.onrender.com/auth/reset-password';

// 운영 환경의 Render 백엔드 주소입니다.
String getBackendUrl() {
  return 'https://archive-saver-web.onrender.com';
}

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Supabase 초기화 (테스트용 더미 정보 우선 기입 허용)
  try {
    await Supabase.initialize(
      url: supabaseUrl,
      publishableKey: supabaseAnonKey,
    );
  } catch (e) {
    debugPrint('Supabase 초기화 실패: $e. 프로젝트 설정 전일 수 있습니다.');
  }

  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Archive Saver',
      theme: ThemeData(
        brightness: Brightness.dark,
        primaryColor: const Color(0xFF0A84FF),
        scaffoldBackgroundColor: const Color(0xFF111827),
        cardColor: const Color(0xFF1F2937),
        useMaterial3: true,
      ),
      home: const AuthGate(),
    );
  }
}

// 로그인 세션 상태에 따라 게이트웨이 화면 제공
class AuthGate extends StatefulWidget {
  const AuthGate({super.key});

  @override
  State<AuthGate> createState() => _AuthGateState();
}

class _AuthGateState extends State<AuthGate> {
  StreamSubscription<AuthState>? _authSubscription;
  bool _isShowingPasswordReset = false;

  @override
  void initState() {
    super.initState();
    _authSubscription = Supabase.instance.client.auth.onAuthStateChange.listen(
      (data) {
        if (data.event != AuthChangeEvent.passwordRecovery ||
            _isShowingPasswordReset) {
          return;
        }

        _isShowingPasswordReset = true;
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!mounted) return;
          Navigator.of(context)
              .push(
                MaterialPageRoute(builder: (_) => const ResetPasswordScreen()),
              )
              .whenComplete(() => _isShowingPasswordReset = false);
        });
      },
      onError: (Object error) {
        debugPrint('인증 링크 처리 실패: $error');
      },
    );
  }

  @override
  void dispose() {
    _authSubscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final session = Supabase.instance.client.auth.currentSession;
    if (session != null) {
      return const DashboardScreen();
    }
    return const LoginScreen();
  }
}

// -----------------------------------------------------------------------------
// 1. 로그인/회원가입 화면
// -----------------------------------------------------------------------------
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  bool _isLoading = false;
  bool _isSendingRecovery = false;

  bool get _isBusy => _isLoading || _isSendingRecovery;

  Future<void> _signIn() async {
    final email = _emailController.text.trim();
    final password = _passwordController.text;

    if (email.isEmpty) {
      _showError('이메일을 입력해주세요.');
      return;
    }
    if (password.isEmpty) {
      _showError('비밀번호를 입력해주세요.');
      return;
    }

    setState(() => _isLoading = true);
    try {
      await Supabase.instance.client.auth.signInWithPassword(
        email: email,
        password: password,
      );
      if (mounted) {
        Navigator.of(context).pushReplacement(
          MaterialPageRoute(builder: (context) => const DashboardScreen()),
        );
      }
    } on AuthException catch (e) {
      _showError(e.message);
    } catch (e) {
      _showError('알 수 없는 오류가 발생했습니다.');
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _signUp() async {
    final email = _emailController.text.trim();
    final password = _passwordController.text;

    if (email.isEmpty) {
      _showError('이메일을 입력해주세요.');
      return;
    }
    if (password.isEmpty) {
      _showError('비밀번호를 입력해주세요.');
      return;
    }

    setState(() => _isLoading = true);
    try {
      await Supabase.instance.client.auth.signUp(
        email: email,
        password: password,
      );
      _showSuccess('회원가입 확인 메일이 전송되었습니다. 메일함을 확인해주세요.');
    } on AuthException catch (e) {
      _showError(e.message);
    } catch (e) {
      _showError('회원가입 실패');
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _sendPasswordRecoveryEmail() async {
    final email = _emailController.text.trim();
    if (email.isEmpty) {
      _showError('복구 이메일을 받을 이메일 주소를 입력해주세요.');
      return;
    }

    setState(() => _isSendingRecovery = true);
    try {
      await Supabase.instance.client.auth.resetPasswordForEmail(
        email,
        redirectTo: passwordRecoveryRedirectUrl,
      );
      _showSuccess('비밀번호 복구 이메일을 전송했습니다. 메일함을 확인해주세요.');
    } on AuthException catch (e) {
      _showError(e.message);
    } catch (e) {
      _showError('복구 이메일을 보내지 못했습니다. 잠시 후 다시 시도해주세요.');
    } finally {
      if (mounted) setState(() => _isSendingRecovery = false);
    }
  }

  void _showError(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), backgroundColor: Colors.redAccent),
    );
  }

  void _showSuccess(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), backgroundColor: Colors.green),
    );
  }

  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Icon(
                Icons.archive_outlined,
                size: 72,
                color: Color(0xFF0A84FF),
              ),
              const SizedBox(height: 16),
              const Text(
                'Archive Saver',
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 28,
                  fontWeight: FontWeight.bold,
                  color: Colors.white,
                ),
              ),
              const SizedBox(height: 8),
              const Text(
                '나만의 아카이브 저장 공간',
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 14, color: Colors.grey),
              ),
              const SizedBox(height: 32),
              TextField(
                controller: _emailController,
                decoration: const InputDecoration(
                  labelText: '이메일',
                  border: OutlineInputBorder(),
                  prefixIcon: Icon(Icons.email_outlined),
                ),
                keyboardType: TextInputType.emailAddress,
              ),
              const SizedBox(height: 16),
              TextField(
                controller: _passwordController,
                decoration: const InputDecoration(
                  labelText: '비밀번호',
                  border: OutlineInputBorder(),
                  prefixIcon: Icon(Icons.lock_outlined),
                ),
                obscureText: true,
              ),
              Align(
                alignment: Alignment.centerRight,
                child: TextButton(
                  onPressed: _isBusy ? null : _sendPasswordRecoveryEmail,
                  child: Text(
                    _isSendingRecovery ? '복구 이메일 전송 중...' : '비밀번호를 잊으셨나요?',
                  ),
                ),
              ),
              const SizedBox(height: 24),
              ElevatedButton(
                onPressed: _isBusy ? null : _signIn,
                style: ElevatedButton.styleFrom(
                  backgroundColor: const Color(0xFF0A84FF),
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(8),
                  ),
                ),
                child: _isLoading
                    ? const CircularProgressIndicator(color: Colors.white)
                    : const Text(
                        '로그인',
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
              ),
              const SizedBox(height: 12),
              OutlinedButton(
                onPressed: _isBusy ? null : _signUp,
                style: OutlinedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(8),
                  ),
                ),
                child: const Text('이메일 회원가입'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// -----------------------------------------------------------------------------
// 2. 비밀번호 재설정 화면
// -----------------------------------------------------------------------------
class ResetPasswordScreen extends StatefulWidget {
  const ResetPasswordScreen({super.key});

  @override
  State<ResetPasswordScreen> createState() => _ResetPasswordScreenState();
}

class _ResetPasswordScreenState extends State<ResetPasswordScreen> {
  final _passwordController = TextEditingController();
  final _passwordConfirmationController = TextEditingController();
  bool _isLoading = false;
  bool _obscurePassword = true;

  Future<void> _updatePassword() async {
    final password = _passwordController.text;
    final passwordConfirmation = _passwordConfirmationController.text;

    if (password.isEmpty) {
      _showError('새 비밀번호를 입력해주세요.');
      return;
    }
    if (password != passwordConfirmation) {
      _showError('비밀번호가 서로 일치하지 않습니다.');
      return;
    }

    setState(() => _isLoading = true);
    try {
      await Supabase.instance.client.auth.updateUser(
        UserAttributes(password: password),
      );
      await Supabase.instance.client.auth.signOut();

      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('비밀번호가 변경되었습니다. 새 비밀번호로 로그인해주세요.'),
          backgroundColor: Colors.green,
        ),
      );
      Navigator.of(context).pushAndRemoveUntil(
        MaterialPageRoute(builder: (_) => const LoginScreen()),
        (_) => false,
      );
    } on AuthException catch (e) {
      _showError(e.message);
    } catch (e) {
      _showError('비밀번호를 변경하지 못했습니다. 복구 링크를 다시 요청해주세요.');
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  void _showError(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), backgroundColor: Colors.redAccent),
    );
  }

  @override
  void dispose() {
    _passwordController.dispose();
    _passwordConfirmationController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('비밀번호 재설정')),
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Icon(Icons.lock_reset, size: 72, color: Color(0xFF0A84FF)),
              const SizedBox(height: 24),
              const Text(
                '새 비밀번호를 입력해주세요.',
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 24),
              TextField(
                controller: _passwordController,
                obscureText: _obscurePassword,
                decoration: InputDecoration(
                  labelText: '새 비밀번호',
                  border: const OutlineInputBorder(),
                  prefixIcon: const Icon(Icons.lock_outlined),
                  suffixIcon: IconButton(
                    onPressed: () {
                      setState(() => _obscurePassword = !_obscurePassword);
                    },
                    icon: Icon(
                      _obscurePassword
                          ? Icons.visibility
                          : Icons.visibility_off,
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 16),
              TextField(
                controller: _passwordConfirmationController,
                obscureText: _obscurePassword,
                onSubmitted: (_) {
                  if (!_isLoading) _updatePassword();
                },
                decoration: const InputDecoration(
                  labelText: '새 비밀번호 확인',
                  border: OutlineInputBorder(),
                  prefixIcon: Icon(Icons.lock_outlined),
                ),
              ),
              const SizedBox(height: 24),
              ElevatedButton(
                onPressed: _isLoading ? null : _updatePassword,
                style: ElevatedButton.styleFrom(
                  backgroundColor: const Color(0xFF0A84FF),
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(vertical: 14),
                ),
                child: _isLoading
                    ? const CircularProgressIndicator(color: Colors.white)
                    : const Text('비밀번호 변경'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// -----------------------------------------------------------------------------
// 3. 보안 확인 페이지 캡처 화면
// -----------------------------------------------------------------------------
class CapturedPageData {
  const CapturedPageData({required this.url, required this.html});

  final String url;
  final String html;
}

enum ArchiveQueueStatus { waiting, saving, verification }

class ArchiveQueueItem {
  ArchiveQueueItem({
    required this.url,
    this.workId,
    this.requiresCapture = false,
  });

  final String url;
  String? workId;
  bool requiresCapture;
  ArchiveQueueStatus status = ArchiveQueueStatus.waiting;
}

class ProtectedPageCaptureScreen extends StatefulWidget {
  const ProtectedPageCaptureScreen({super.key, required this.url});

  final String url;

  @override
  State<ProtectedPageCaptureScreen> createState() =>
      _ProtectedPageCaptureScreenState();
}

class _ProtectedPageCaptureScreenState
    extends State<ProtectedPageCaptureScreen> {
  late final WebViewController _controller;
  Timer? _pageMonitor;
  DateTime? _pageFinishedAt;
  bool _isPageLoaded = false;
  bool _isCapturing = false;
  int _readyCheckCount = 0;
  String _statusMessage = '페이지를 불러오는 중입니다...';

  @override
  void initState() {
    super.initState();
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageStarted: (_) {
            _pageFinishedAt = null;
            _readyCheckCount = 0;
            if (mounted) {
              setState(() {
                _isPageLoaded = false;
                _statusMessage = '페이지를 불러오는 중입니다...';
              });
            }
          },
          onPageFinished: (_) {
            _pageFinishedAt = DateTime.now();
            if (mounted) {
              setState(() {
                _isPageLoaded = true;
                _statusMessage = '보안 확인 완료를 기다리는 중입니다...';
              });
            }
          },
        ),
      )
      ..loadRequest(Uri.parse(widget.url));
    _pageMonitor = Timer.periodic(
      const Duration(seconds: 1),
      (_) => _checkPageReady(),
    );
  }

  @override
  void dispose() {
    _pageMonitor?.cancel();
    super.dispose();
  }

  String _normalizeCapturedHtml(Object result) {
    final raw = result.toString();
    if (raw.startsWith('"') && raw.endsWith('"')) {
      try {
        final decoded = jsonDecode(raw);
        if (decoded is String) return decoded;
      } catch (_) {
        // 플랫폼이 이미 일반 문자열로 반환했다면 원본을 사용합니다.
      }
    }
    return raw;
  }

  bool _isSecurityChallenge(String title, String bodyText) {
    final content = '$title\n$bodyText'.toLowerCase();
    const challengeMarkers = [
      '잠시만 기다리십시오',
      '보안 확인 수행 중',
      '사용자가 봇이 아님을 확인',
      '악의적인 봇으로부터 보호',
      'just a moment',
      'verify you are human',
      'checking your browser',
      'cf-chl-',
    ];
    return challengeMarkers.any(content.contains);
  }

  Future<void> _checkPageReady() async {
    if (!_isPageLoaded || _isCapturing || !mounted) return;
    final finishedAt = _pageFinishedAt;
    if (finishedAt == null ||
        DateTime.now().difference(finishedAt) < const Duration(seconds: 2)) {
      return;
    }

    try {
      final stateResult = await _controller.runJavaScriptReturningResult(
        '''JSON.stringify({
          readyState: document.readyState,
          title: document.title || '',
          bodyText: document.body ? document.body.innerText.slice(0, 5000) : ''
        })''',
      );
      final stateText = _normalizeCapturedHtml(stateResult);
      final state = jsonDecode(stateText);
      if (state is! Map<String, dynamic>) return;

      final readyState = state['readyState']?.toString() ?? '';
      final title = state['title']?.toString() ?? '';
      final bodyText = state['bodyText']?.toString() ?? '';

      if (_isSecurityChallenge(title, bodyText)) {
        _readyCheckCount = 0;
        if (mounted && _statusMessage != '보안 확인을 완료해주세요.') {
          setState(() => _statusMessage = '보안 확인을 완료해주세요.');
        }
        return;
      }

      if (readyState != 'complete' || bodyText.trim().length < 100) {
        _readyCheckCount = 0;
        return;
      }

      _readyCheckCount += 1;
      if (_readyCheckCount < 2) return;
      await _captureAndReturn();
    } catch (e) {
      debugPrint('페이지 상태 확인 실패: $e');
      _readyCheckCount = 0;
    }
  }

  Future<void> _captureAndReturn() async {
    if (_isCapturing || !mounted) return;
    _isCapturing = true;
    setState(() => _statusMessage = '확인 완료. 페이지를 캡처하는 중입니다...');

    try {
      await _controller.runJavaScript(
        'window.scrollTo(0, document.documentElement.scrollHeight);',
      );
      await Future<void>.delayed(const Duration(milliseconds: 700));

      final result = await _controller.runJavaScriptReturningResult(
        'document.documentElement.outerHTML',
      );
      final html = _normalizeCapturedHtml(result);
      final currentUrl = await _controller.currentUrl() ?? widget.url;

      if (!html.toLowerCase().contains('<html')) {
        throw StateError('현재 페이지의 HTML을 읽지 못했습니다.');
      }

      if (!mounted) return;
      Navigator.of(context).pop(CapturedPageData(url: currentUrl, html: html));
    } catch (e) {
      _isCapturing = false;
      _readyCheckCount = 0;
      if (mounted) {
        setState(() => _statusMessage = '자동 캡처에 실패했습니다. 다시 확인 중입니다...');
      }
      debugPrint('페이지 자동 캡처 실패: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('보안 확인 후 저장'),
        actions: [
          IconButton(
            tooltip: '새로고침',
            onPressed: _isCapturing ? null : _controller.reload,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: Column(
        children: [
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(12),
            color: const Color(0xFF1F2937),
            child: const Text(
              '보안 확인이 표시되면 직접 완료해주세요. 실제 문서가 열리면 자동으로 캡처합니다.',
              style: TextStyle(color: Colors.white70),
            ),
          ),
          Expanded(child: WebViewWidget(controller: _controller)),
          SafeArea(
            top: false,
            child: Container(
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              color: const Color(0xFF1F2937),
              child: Text(
                _statusMessage,
                textAlign: TextAlign.center,
                style: const TextStyle(color: Colors.white70),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class ScreenshotArchiveViewer extends StatefulWidget {
  const ScreenshotArchiveViewer({
    super.key,
    required this.archiveId,
    this.title = '저장된 화면',
  });

  final String archiveId;
  final String title;

  @override
  State<ScreenshotArchiveViewer> createState() =>
      _ScreenshotArchiveViewerState();
}

class _ScreenshotArchiveViewerState extends State<ScreenshotArchiveViewer> {
  late final Future<Uint8List> _imageFuture = _loadImage();

  Future<Uint8List> _loadImage() async {
    // 1. 로컬 캐시 확인
    final cachedBytes = await OfflineArchiveManager.instance.getCachedImageBytes(
      widget.archiveId,
    );
    if (cachedBytes != null) {
      return cachedBytes;
    }

    // 2. 원격 서버에서 로드
    final session = Supabase.instance.client.auth.currentSession;
    if (session == null) throw Exception('로그인이 필요합니다.');
    final response = await http.get(
      Uri.parse('${getBackendUrl()}/api/archive/${widget.archiveId}/file'),
      headers: {
        'Authorization': 'Bearer ${session.accessToken}',
        'Bypass-Tunnel-Reminder': 'true',
      },
    );
    if (response.statusCode != 200) {
      throw Exception('화면을 불러오지 못했습니다. (${response.statusCode})');
    }

    // 다음 번 오프라인 사용을 위해 백그라운드 캐싱
    unawaited(
      OfflineArchiveManager.instance.cacheScreenshotArchive(
        archiveId: widget.archiveId,
        backendUrl: getBackendUrl(),
        accessToken: session.accessToken,
      ),
    );

    return response.bodyBytes;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: Column(
          children: [
            // 미니멀 상단 헤더 (제목 + 닫기 버튼만 제공, 주소창/하단바 없음)
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
                  Expanded(
                    child: Text(
                      widget.title.isNotEmpty ? widget.title : '저장된 화면',
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
            Expanded(
              child: FutureBuilder<Uint8List>(
                future: _imageFuture,
                builder: (context, snapshot) {
                  if (snapshot.connectionState != ConnectionState.done) {
                    return const Center(
                      child: CircularProgressIndicator(
                        color: Color(0xFF0A84FF),
                      ),
                    );
                  }
                  if (snapshot.hasError || snapshot.data == null) {
                    return Center(
                      child: Padding(
                        padding: const EdgeInsets.all(24),
                        child: Text(
                          snapshot.error.toString(),
                          textAlign: TextAlign.center,
                          style: const TextStyle(color: Colors.white70),
                        ),
                      ),
                    );
                  }
                  return InteractiveViewer(
                    minScale: 0.5,
                    maxScale: 8,
                    boundaryMargin: const EdgeInsets.all(80),
                    child: Center(
                      child: Image.memory(
                        snapshot.data!,
                        fit: BoxFit.contain,
                        filterQuality: FilterQuality.high,
                        gaplessPlayback: true,
                      ),
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// -----------------------------------------------------------------------------
// 4. 대시보드 화면
// -----------------------------------------------------------------------------
class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen>
    with WidgetsBindingObserver {
  static const MethodChannel _backgroundArchiveChannel = MethodChannel(
    'com.archivesaver.frontendflutter/background_archive',
  );

  bool _isStorageConnected = false;
  String? _connectedProvider;
  List<dynamic> _archives = [];
  bool _isLoadingStorage = false;
  bool _isLoadingArchives = false;
  bool _awaitingStorageConnection = false;
  bool _isScreenCaptureEnabled = false;
  StreamSubscription<List<SharedMediaFile>>? _shareIntentSubscription;
  Timer? _backgroundJobPoller;
  final List<ArchiveQueueItem> _archiveQueue = [];
  bool _isProcessingArchiveQueue = false;
  bool _isSyncingBackgroundJobs = false;

  // 오프라인 저장소 상태 관리
  Set<String> _cachedArchiveIds = {};
  bool _isSyncingOffline = false;
  int _offlineSyncCurrent = 0;
  int _offlineSyncTotal = 0;
  String _offlineSyncStatus = '';
  int _offlineStorageBytes = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _fetchStorageStatus();
    _fetchArchives();
    _refreshOfflineStatus();
    _initShareIntentListener();
    if (_supportsBackgroundArchiving) {
      unawaited(_configureScreenCapture());
      unawaited(_syncBackgroundArchiveJobs());
      _backgroundJobPoller = Timer.periodic(
        const Duration(seconds: 2),
        (_) => unawaited(_syncBackgroundArchiveJobs()),
      );
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _shareIntentSubscription?.cancel();
    _backgroundJobPoller?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      if (_awaitingStorageConnection) {
        _awaitingStorageConnection = false;
        unawaited(_refreshAfterStorageConnection());
      }
      if (_supportsBackgroundArchiving) {
        unawaited(_configureScreenCapture());
        unawaited(_syncBackgroundArchiveJobs());
        unawaited(_fetchArchives());
      }
      unawaited(_refreshOfflineStatus());
    }
  }

  bool get _supportsBackgroundArchiving =>
      defaultTargetPlatform == TargetPlatform.android;

  Future<void> _configureScreenCapture() async {
    if (!_supportsBackgroundArchiving) return;
    final session = Supabase.instance.client.auth.currentSession;
    if (session == null) return;
    try {
      final enabled = await _backgroundArchiveChannel.invokeMethod<bool>(
        'configureScreenCapture',
        {'accessToken': session.accessToken, 'backendUrl': getBackendUrl()},
      );
      if (mounted) {
        setState(() => _isScreenCaptureEnabled = enabled ?? false);
      }
    } on PlatformException catch (error) {
      debugPrint('화면 저장 단축키 설정 실패: ${error.message}');
    }
  }

  Future<void> _requestScreenCaptureAccess() async {
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('화면 저장 단축키 사용'),
        content: const Text(
          'Archive Saver는 사용자가 볼륨 아래 버튼을 두 번 눌렀을 때만 '
          '현재 화면을 캡처합니다. 캡처 이미지는 사용자의 연결된 클라우드 '
          '스토리지에 저장되며, 앱은 평상시 화면이나 키 입력을 기록하지 않습니다.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('동의하고 설정 열기'),
          ),
        ],
      ),
    );
    if (accepted != true) return;
    await _backgroundArchiveChannel.invokeMethod<void>(
      'openAccessibilitySettings',
    );
  }

  Future<void> _refreshAfterStorageConnection() async {
    await _fetchStorageStatus();
    if (mounted && _isStorageConnected) {
      _showMessage('스토리지 연동이 완료되었습니다.');
    }
  }

  // 공유 인텐트 리스너 설정
  void _initShareIntentListener() {
    // 앱이 백그라운드에 있다가 공유 액션으로 들어오는 경우
    _shareIntentSubscription = ReceiveSharingIntent.instance
        .getMediaStream()
        .listen(
          (value) {
            if (value.isNotEmpty) {
              _handleSharedUrl(value.first.path);
            }
          },
          onError: (err) {
            debugPrint("공유 데이터 수신 에러: $err");
          },
        );

    // 앱이 완전히 닫혀있다가 공유 액션으로 켜지는 경우
    ReceiveSharingIntent.instance.getInitialMedia().then((value) {
      if (value.isNotEmpty) {
        _handleSharedUrl(value.first.path);
      }
      ReceiveSharingIntent.instance.reset();
    });
  }

  // 감지된 공유 URL을 저장 대기열에 추가
  Future<void> _handleSharedUrl(String url) async {
    final uri = Uri.tryParse(url.trim());
    final isWebUrl =
        uri != null &&
        (uri.scheme == 'http' || uri.scheme == 'https') &&
        uri.host.isNotEmpty;
    if (!isWebUrl) {
      debugPrint('아카이빙 대상이 아닌 앱 링크를 무시했습니다: $url');
      return;
    }

    final normalizedUrl = uri.toString();
    final isDuplicate = _archiveQueue.any((item) => item.url == normalizedUrl);
    if (isDuplicate) {
      _showMessage('이미 저장 중이거나 대기 중인 페이지입니다.');
      return;
    }

    final isNamuWiki =
        uri.host == 'namu.wiki' || uri.host.endsWith('.namu.wiki');
    final queueItem = ArchiveQueueItem(url: normalizedUrl);
    if (_supportsBackgroundArchiving && !isNamuWiki) {
      queueItem.workId = 'pending';
    }
    setState(() => _archiveQueue.add(queueItem));

    if (_supportsBackgroundArchiving && !isNamuWiki) {
      unawaited(_enqueueBackgroundArchive(queueItem));
    } else {
      unawaited(_processArchiveQueue());
    }
  }

  Future<void> _enqueueBackgroundArchive(ArchiveQueueItem item) async {
    final session = Supabase.instance.client.auth.currentSession;
    if (session == null) {
      if (mounted) setState(() => _archiveQueue.remove(item));
      _showError('로그인이 필요합니다.');
      return;
    }

    try {
      final workId = await _backgroundArchiveChannel
          .invokeMethod<String>('enqueueArchive', {
            'url': item.url,
            'accessToken': session.accessToken,
            'backendUrl': getBackendUrl(),
            'createdAt': DateTime.now().millisecondsSinceEpoch,
          });
      if (!mounted) return;
      if (workId == null || workId.isEmpty) {
        throw PlatformException(
          code: 'EMPTY_WORK_ID',
          message: '백그라운드 작업 ID를 받지 못했습니다.',
        );
      }
      setState(() => item.workId = workId);
      unawaited(_syncBackgroundArchiveJobs());
    } catch (error) {
      if (mounted) setState(() => _archiveQueue.remove(item));
      _showError('백그라운드 저장을 시작하지 못했습니다: $error');
    }
  }

  Future<void> _syncBackgroundArchiveJobs() async {
    if (!_supportsBackgroundArchiving || _isSyncingBackgroundJobs) return;
    _isSyncingBackgroundJobs = true;
    try {
      final rawJobs =
          await _backgroundArchiveChannel.invokeMethod<List<dynamic>>(
            'getArchiveJobs',
          ) ??
          const [];
      if (!mounted) return;

      var hasFinishedJobs = false;
      var shouldRefreshArchives = false;
      var shouldProcessCapture = false;
      final successMessages = <String>[];
      final errorMessages = <String>[];

      setState(() {
        for (final rawJob in rawJobs) {
          final job = Map<String, dynamic>.from(rawJob as Map);
          final id = job['id'] as String?;
          final url = job['url'] as String?;
          final state = job['state'] as String? ?? 'ENQUEUED';
          if (id == null || url == null) continue;

          ArchiveQueueItem? item;
          for (final queuedItem in _archiveQueue) {
            if (queuedItem.workId == id) {
              item = queuedItem;
              break;
            }
          }
          if (item == null) {
            for (final queuedItem in _archiveQueue) {
              if (queuedItem.workId == 'pending' && queuedItem.url == url) {
                queuedItem.workId = id;
                item = queuedItem;
                break;
              }
            }
          }

          final isFinished =
              state == 'SUCCEEDED' || state == 'FAILED' || state == 'CANCELLED';
          if (item == null && !isFinished) {
            item = ArchiveQueueItem(url: url, workId: id);
            _archiveQueue.add(item);
          }

          if (state == 'RUNNING') {
            item?.status = ArchiveQueueStatus.saving;
          } else if (state == 'ENQUEUED' || state == 'BLOCKED') {
            item?.status = ArchiveQueueStatus.waiting;
          } else if (isFinished) {
            hasFinishedJobs = true;
            if (item != null) _archiveQueue.remove(item);

            if (state == 'SUCCEEDED') {
              shouldRefreshArchives = true;
              successMessages.add(
                (job['message'] as String?) ?? '페이지가 저장되었습니다.',
              );
            } else if (job['needsVerification'] == true) {
              final alreadyQueued = _archiveQueue.any(
                (queuedItem) => queuedItem.url == url,
              );
              if (!alreadyQueued) {
                _archiveQueue.add(
                  ArchiveQueueItem(url: url, requiresCapture: true),
                );
                shouldProcessCapture = true;
              }
            } else {
              errorMessages.add((job['error'] as String?) ?? '페이지 저장에 실패했습니다.');
            }
          }
        }
      });

      if (hasFinishedJobs) {
        await _backgroundArchiveChannel.invokeMethod<void>(
          'clearFinishedArchiveJobs',
        );
      }
      if (shouldRefreshArchives) await _fetchArchives();
      for (final message in successMessages) {
        if (mounted) _showMessage(message);
      }
      for (final message in errorMessages) {
        if (mounted) _showError('저장 실패: $message');
      }
      if (shouldProcessCapture) unawaited(_processArchiveQueue());
    } on PlatformException catch (error) {
      debugPrint('백그라운드 저장 상태 확인 실패: ${error.message}');
    } finally {
      _isSyncingBackgroundJobs = false;
    }
  }

  Future<void> _processArchiveQueue() async {
    if (_isProcessingArchiveQueue) return;
    _isProcessingArchiveQueue = true;

    try {
      while (mounted) {
        ArchiveQueueItem? item;
        for (final queuedItem in _archiveQueue) {
          if (queuedItem.workId == null) {
            item = queuedItem;
            break;
          }
        }
        if (item == null) break;
        final currentItem = item;
        setState(() => currentItem.status = ArchiveQueueStatus.saving);
        await _processArchiveQueueItem(currentItem);
        if (!mounted) return;
        setState(() => _archiveQueue.remove(currentItem));
      }
    } finally {
      _isProcessingArchiveQueue = false;
      if (mounted && _archiveQueue.any((item) => item.workId == null)) {
        unawaited(_processArchiveQueue());
      }
    }
  }

  Future<bool> _processArchiveQueueItem(ArchiveQueueItem item) async {
    final session = Supabase.instance.client.auth.currentSession;
    if (session == null) {
      _showError('로그인이 필요합니다.');
      return false;
    }

    final uri = Uri.parse(item.url);
    if (item.requiresCapture ||
        uri.host == 'namu.wiki' ||
        uri.host.endsWith('.namu.wiki')) {
      final capturedPage = await _captureProtectedPage(item);
      if (capturedPage == null) return false;
      return _saveCapturedPage(capturedPage);
    }

    try {
      final response = await http.post(
        Uri.parse('${getBackendUrl()}/api/save-html'),
        headers: {
          'Content-Type': 'application/json',
          'Authorization': 'Bearer ${session.accessToken}',
          'Bypass-Tunnel-Reminder': 'true',
        },
        body: jsonEncode({'url': item.url}),
      );

      if (response.statusCode == 200) {
        final resData = jsonDecode(response.body);
        _showMessage('저장 성공: ${resData['message'] ?? ""}');
        await _fetchArchives();
        return true;
      }

      final resData = _decodeResponseBody(response.body);
      if (response.statusCode == 409) {
        final capturedPage = await _captureProtectedPage(item);
        if (capturedPage == null) return false;
        return _saveCapturedPage(capturedPage);
      }
      _showError('저장 실패: ${resData['error'] ?? "서버 오류"}');
      return false;
    } catch (e) {
      _showError('네트워크 오류가 발생했습니다: $e');
      return false;
    }
  }

  Map<String, dynamic> _decodeResponseBody(String body) {
    try {
      final decoded = jsonDecode(body);
      if (decoded is Map<String, dynamic>) return decoded;
    } catch (_) {
      // JSON이 아닌 서버 응답은 아래의 기본 오류로 처리합니다.
    }
    return {'error': body.trim().isEmpty ? '서버 오류' : body.trim()};
  }

  Future<CapturedPageData?> _captureProtectedPage(ArchiveQueueItem item) async {
    if (!mounted) return null;
    setState(() => item.status = ArchiveQueueStatus.verification);
    final capturedPage = await Navigator.of(context).push<CapturedPageData>(
      MaterialPageRoute(
        builder: (_) => ProtectedPageCaptureScreen(url: item.url),
      ),
    );
    if (capturedPage != null && mounted) {
      setState(() => item.status = ArchiveQueueStatus.saving);
      await Future<void>.delayed(const Duration(milliseconds: 150));
    }
    return capturedPage;
  }

  Future<bool> _saveCapturedPage(CapturedPageData capturedPage) async {
    final session = Supabase.instance.client.auth.currentSession;
    if (session == null) {
      _showError('로그인이 필요합니다.');
      return false;
    }

    if (_supportsBackgroundArchiving) {
      try {
        await _backgroundArchiveChannel
            .invokeMethod<String>('enqueueCapturedArchive', {
              'url': capturedPage.url,
              'html': capturedPage.html,
              'accessToken': session.accessToken,
              'backendUrl': getBackendUrl(),
              'createdAt': DateTime.now().millisecondsSinceEpoch,
            });
        unawaited(_syncBackgroundArchiveJobs());
        return true;
      } catch (error) {
        _showError('백그라운드 저장을 시작하지 못했습니다: $error');
        return false;
      }
    }

    try {
      final response = await http.post(
        Uri.parse('${getBackendUrl()}/api/save-html'),
        headers: {
          'Content-Type': 'application/json',
          'Authorization': 'Bearer ${session.accessToken}',
        },
        body: jsonEncode({
          'url': capturedPage.url,
          'html': capturedPage.html,
          'clientCaptureMode': 'flutter-webview',
        }),
      );

      final responseData = _decodeResponseBody(response.body);
      if (response.statusCode == 200) {
        _showMessage('페이지가 저장되었습니다.');
        await _fetchArchives();
        return true;
      } else if (response.statusCode == 409) {
        _showError('보안 확인 페이지가 감지되어 저장하지 않았습니다.');
      } else {
        _showError('저장 실패: ${responseData['error'] ?? "서버 오류"}');
      }
      return false;
    } catch (e) {
      _showError('네트워크 오류가 발생했습니다: $e');
      return false;
    }
  }

  // 사용자별 스토리지 연결 정보 가져오기
  Future<void> _fetchStorageStatus() async {
    final session = Supabase.instance.client.auth.currentSession;
    if (session == null) return;

    if (mounted) setState(() => _isLoadingStorage = true);
    try {
      final res = await http.get(
        Uri.parse('${getBackendUrl()}/api/user/storage-status'),
        headers: {
          'Authorization': 'Bearer ${session.accessToken}',
          'Bypass-Tunnel-Reminder': 'true',
        },
      );
      if (res.statusCode == 200) {
        final data = jsonDecode(res.body);
        if (!mounted) return;
        setState(() {
          _isStorageConnected = data['connected'] ?? false;
          _connectedProvider = data['provider'];
        });
      }
    } catch (e) {
      debugPrint('스토리지 연동 정보 확인 실패: $e');
    } finally {
      if (mounted) setState(() => _isLoadingStorage = false);
    }
  }

  // 사용자 아카이브 내역 조회 (Supabase DB 직접 연동)
  Future<void> _fetchArchives() async {
    final client = Supabase.instance.client;
    if (client.auth.currentSession == null) return;

    setState(() => _isLoadingArchives = true);
    try {
      // Row-Level Security(RLS) 정책에 의해 현재 유저의 아카이브 데이터만 받아옵니다.
      final res = await client
          .from('archives')
          .select('*')
          .order('created_at', ascending: false);
      setState(() {
        _archives = res as List<dynamic>;
      });
      unawaited(_refreshOfflineStatus());
    } catch (e) {
      debugPrint('아카이브 목록 조회 실패: $e');
    } finally {
      setState(() => _isLoadingArchives = false);
    }
  }

  Future<void> _refreshOfflineStatus() async {
    final cachedIds =
        await OfflineArchiveManager.instance.getAllCachedArchiveIds();
    final totalBytes =
        await OfflineArchiveManager.instance.getOfflineStorageSizeBytes();
    if (mounted) {
      setState(() {
        _cachedArchiveIds = cachedIds;
        _offlineStorageBytes = totalBytes;
      });
    }
  }

  Future<void> _syncAllOffline() async {
    if (_isSyncingOffline || _archives.isEmpty) return;
    final session = Supabase.instance.client.auth.currentSession;
    setState(() {
      _isSyncingOffline = true;
      _offlineSyncCurrent = 0;
      _offlineSyncTotal = _archives.length;
      _offlineSyncStatus = '오프라인 동기화 시작...';
    });

    try {
      await OfflineArchiveManager.instance.syncAllArchives(
        archives: _archives,
        backendUrl: getBackendUrl(),
        accessToken: session?.accessToken,
        onProgress: (current, total, status) {
          if (mounted) {
            setState(() {
              _offlineSyncCurrent = current;
              _offlineSyncTotal = total;
              _offlineSyncStatus = status;
            });
          }
        },
      );
      _showMessage('모든 페이지 오프라인 저장이 완료되었습니다.');
    } catch (e) {
      _showError('오프라인 동기화 중 오류가 발생했습니다: $e');
    } finally {
      if (mounted) {
        setState(() => _isSyncingOffline = false);
        await _refreshOfflineStatus();
      }
    }
  }

  Future<void> _toggleItemOffline(dynamic item) async {
    final id = item['id']?.toString() ?? '';
    final title = item['title']?.toString() ?? '제목 없음';
    final url = item['url']?.toString() ?? '';
    final isScreenshot = url.startsWith('screenshot://');
    if (id.isEmpty) return;

    final isCached = _cachedArchiveIds.contains(id);
    if (isCached) {
      final confirm = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('오프라인 캐시 삭제'),
          content: Text('\'$title\'의 오프라인 사본을 기기에서 삭제하시겠습니까?'),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('취소'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              style: FilledButton.styleFrom(backgroundColor: Colors.redAccent),
              child: const Text('삭제'),
            ),
          ],
        ),
      );
      if (confirm == true) {
        await OfflineArchiveManager.instance.deleteArchive(id);
        await _refreshOfflineStatus();
        _showMessage('오프라인 사본이 삭제되었습니다.');
      }
    } else {
      _showMessage('\'$title\' 오프라인 다운로드 중...');
      bool success = false;
      if (isScreenshot) {
        final session = Supabase.instance.client.auth.currentSession;
        if (session != null) {
          success = await OfflineArchiveManager.instance.cacheScreenshotArchive(
            archiveId: id,
            backendUrl: getBackendUrl(),
            accessToken: session.accessToken,
          );
        }
      } else {
        success = await OfflineArchiveManager.instance.cacheHtmlArchive(
          archiveId: id,
          backendUrl: getBackendUrl(),
        );
      }
      if (success) {
        await _refreshOfflineStatus();
        _showMessage('\'$title\' 오프라인 저장 완료');
      } else {
        _showError('오프라인 저장 실패');
      }
    }
  }

  Future<void> _confirmClearOfflineCache() async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('전체 오프라인 캐시 비우기'),
        content: const Text(
          '기기에 저장된 모든 오프라인 페이지를 삭제하시겠습니까?\n클라우드 원본은 안전하게 유지됩니다.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            style: FilledButton.styleFrom(backgroundColor: Colors.redAccent),
            child: const Text('비우기'),
          ),
        ],
      ),
    );
    if (confirm == true) {
      await OfflineArchiveManager.instance.clearAllArchives();
      await _refreshOfflineStatus();
      _showMessage('전체 오프라인 캐시가 비워졌습니다.');
    }
  }

  void _openRaindropImporter() {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => RaindropImporterDialog(
        backendUrl: getBackendUrl(),
        onImportCompleted: () {
          _fetchArchives();
          _refreshOfflineStatus();
        },
      ),
    );
  }

  // 스토리지 연결 해제
  Future<void> _disconnectStorage() async {
    final session = Supabase.instance.client.auth.currentSession;
    if (session == null) return;

    try {
      final res = await http.post(
        Uri.parse('${getBackendUrl()}/api/user/storage-disconnect'),
        headers: {
          'Authorization': 'Bearer ${session.accessToken}',
          'Bypass-Tunnel-Reminder': 'true',
        },
      );
      if (res.statusCode == 200) {
        _showMessage('연결 해제 성공');
        _fetchStorageStatus();
      }
    } catch (e) {
      _showError('연결 해제 실패: $e');
    }
  }

  // 스토리지 OAuth 연동 웹페이지 열기
  void _connectStorage(String provider) async {
    final session = Supabase.instance.client.auth.currentSession;
    if (session == null) return;

    final token = session.accessToken;
    final connectUrl =
        '${getBackendUrl()}/api/auth/$provider/connect?token=$token';
    final uri = Uri.parse(connectUrl);

    if (await canLaunchUrl(uri)) {
      _awaitingStorageConnection = true;
      final launched = await launchUrl(
        uri,
        mode: LaunchMode.externalApplication,
      );
      if (!launched) {
        _awaitingStorageConnection = false;
        _showError('웹 브라우저를 열 수 없습니다.');
      }
    } else {
      _showError('웹 브라우저를 열 수 없습니다.');
    }
  }

  Future<void> _logout() async {
    await Supabase.instance.client.auth.signOut();
    if (mounted) {
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (context) => const LoginScreen()),
      );
    }
  }

  void _showMessage(String text) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
  }

  void _showError(String text) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(text), backgroundColor: Colors.redAccent),
    );
  }

  String _queueStatusLabel(ArchiveQueueStatus status) {
    switch (status) {
      case ArchiveQueueStatus.waiting:
        return '대기 중';
      case ArchiveQueueStatus.saving:
        return '저장 중';
      case ArchiveQueueStatus.verification:
        return '보안 확인 대기';
    }
  }

  IconData _queueStatusIcon(ArchiveQueueStatus status) {
    switch (status) {
      case ArchiveQueueStatus.waiting:
        return Icons.schedule;
      case ArchiveQueueStatus.saving:
        return Icons.archive_outlined;
      case ArchiveQueueStatus.verification:
        return Icons.verified_user_outlined;
    }
  }

  @override
  Widget build(BuildContext context) {
    final userEmail =
        Supabase.instance.client.auth.currentUser?.email ?? 'Unknown User';

    return Scaffold(
      appBar: AppBar(
        title: const Text('내 아카이브 대시보드'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () {
              _fetchStorageStatus();
              _fetchArchives();
            },
          ),
          IconButton(icon: const Icon(Icons.logout), onPressed: _logout),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // 유저 정보 카드
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Row(
                  children: [
                    const CircleAvatar(
                      backgroundColor: Color(0xFF0A84FF),
                      child: Icon(Icons.person, color: Colors.white),
                    ),
                    const SizedBox(width: 16),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text(
                            '로그인 계정',
                            style: TextStyle(color: Colors.grey, fontSize: 12),
                          ),
                          Text(
                            userEmail,
                            style: const TextStyle(
                              fontSize: 16,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),

            if (_supportsBackgroundArchiving) ...[
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        '빠른 화면 저장',
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        _isScreenCaptureEnabled
                            ? '사용 중 · 볼륨 아래 버튼을 빠르게 2번 누르세요.'
                            : '접근성 설정에서 Archive Saver 화면 저장 단축키를 켜주세요.',
                        style: TextStyle(
                          color: _isScreenCaptureEnabled
                              ? Colors.greenAccent
                              : Colors.white70,
                        ),
                      ),
                      if (!_isScreenCaptureEnabled) ...[
                        const SizedBox(height: 12),
                        FilledButton.icon(
                          onPressed: _requestScreenCaptureAccess,
                          icon: const Icon(Icons.screenshot_monitor),
                          label: const Text('화면 저장 단축키 켜기'),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 16),
            ],

            if (_archiveQueue.isNotEmpty) ...[
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '저장 대기열 (${_archiveQueue.length})',
                        style: const TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 8),
                      ..._archiveQueue.map((item) {
                        final uri = Uri.tryParse(item.url);
                        return ListTile(
                          dense: true,
                          contentPadding: EdgeInsets.zero,
                          leading: Icon(
                            _queueStatusIcon(item.status),
                            color: item.status == ArchiveQueueStatus.waiting
                                ? Colors.grey
                                : const Color(0xFF0A84FF),
                          ),
                          title: Text(
                            uri?.host.isNotEmpty == true ? uri!.host : item.url,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                          subtitle: Text(
                            item.url,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                          trailing: Text(
                            _queueStatusLabel(item.status),
                            style: TextStyle(
                              color: item.status == ArchiveQueueStatus.waiting
                                  ? Colors.grey
                                  : Colors.lightBlueAccent,
                            ),
                          ),
                        );
                      }),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 16),
            ],

            // 스토리지 연결 현황 카드
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      '스토리지 연동 상태 (방법 B)',
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.bold,
                        color: Colors.grey,
                      ),
                    ),
                    const SizedBox(height: 16),
                    _isLoadingStorage
                        ? const Center(
                            child: Text(
                              '연동 상태를 확인하는 중...',
                              style: TextStyle(color: Colors.grey),
                            ),
                          )
                        : _isStorageConnected
                        ? Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              Row(
                                children: [
                                  Icon(
                                    _connectedProvider == 'dropbox'
                                        ? Icons.cloud_queue
                                        : Icons.play_arrow,
                                    color: Colors.greenAccent,
                                  ),
                                  const SizedBox(width: 8),
                                  Text(
                                    '연동 완료 (${_connectedProvider?.toUpperCase()})',
                                    style: const TextStyle(
                                      fontSize: 16,
                                      fontWeight: FontWeight.bold,
                                      color: Colors.greenAccent,
                                    ),
                                  ),
                                ],
                              ),
                              TextButton(
                                onPressed: _disconnectStorage,
                                child: const Text(
                                  '연결 해제',
                                  style: TextStyle(color: Colors.redAccent),
                                ),
                              ),
                            ],
                          )
                        : Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              const Text(
                                '저장할 개인 클라우드 스토리지를 연결해주세요.',
                                style: TextStyle(color: Colors.amber),
                              ),
                              const SizedBox(height: 12),
                              ElevatedButton.icon(
                                onPressed: () => _connectStorage('dropbox'),
                                icon: const Icon(Icons.cloud_circle_outlined),
                                label: const Text('Dropbox 연결하기'),
                                style: ElevatedButton.styleFrom(
                                  backgroundColor: const Color(0xFF0061FE),
                                  foregroundColor: Colors.white,
                                ),
                              ),
                              const SizedBox(height: 8),
                              ElevatedButton.icon(
                                onPressed: () => _connectStorage('google'),
                                icon: const Icon(Icons.add_to_drive_outlined),
                                label: const Text('Google Drive 연결하기'),
                                style: ElevatedButton.styleFrom(
                                  backgroundColor: const Color(0xFF34A853),
                                  foregroundColor: Colors.white,
                                ),
                              ),
                            ],
                          ),
                  ],
                ),
              ),
            ),
            // 오프라인 저장소 및 동기화 카드
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Row(
                          children: [
                            Icon(
                              Icons.offline_pin_outlined,
                              color: Color(0xFF0A84FF),
                            ),
                            SizedBox(width: 8),
                            Text(
                              '오프라인 저장소',
                              style: TextStyle(
                                fontSize: 15,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                          ],
                        ),
                        Text(
                          '${_cachedArchiveIds.length} / ${_archives.length}개 보관됨 (${OfflineArchiveManager.formatBytes(_offlineStorageBytes)})',
                          style: const TextStyle(
                            fontSize: 12,
                            color: Colors.grey,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    if (_isSyncingOffline) ...[
                      LinearProgressIndicator(
                        value: _offlineSyncTotal > 0
                            ? _offlineSyncCurrent / _offlineSyncTotal
                            : null,
                        backgroundColor: Colors.grey.shade800,
                        valueColor: const AlwaysStoppedAnimation<Color>(
                          Color(0xFF0A84FF),
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        _offlineSyncStatus.isNotEmpty
                            ? _offlineSyncStatus
                            : '$_offlineSyncCurrent / $_offlineSyncTotal 다운로드 중...',
                        style: const TextStyle(
                          fontSize: 12,
                          color: Colors.lightBlueAccent,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ] else ...[
                      Row(
                        children: [
                          Expanded(
                            child: FilledButton.icon(
                              onPressed:
                                  _archives.isEmpty ? null : _syncAllOffline,
                              icon: const Icon(Icons.download),
                              label: const Text('모든 페이지 오프라인 저장'),
                              style: FilledButton.styleFrom(
                                backgroundColor: const Color(0xFF0A84FF),
                                foregroundColor: Colors.white,
                              ),
                            ),
                          ),
                          if (_cachedArchiveIds.isNotEmpty) ...[
                            const SizedBox(width: 8),
                            IconButton(
                              tooltip: '오프라인 캐시 비우기',
                              icon: const Icon(
                                Icons.delete_outline,
                                color: Colors.redAccent,
                              ),
                              onPressed: _confirmClearOfflineCache,
                            ),
                          ],
                        ],
                      ),
                      const SizedBox(height: 8),
                      OutlinedButton.icon(
                        onPressed: _openRaindropImporter,
                        icon: const Icon(Icons.cloud_download_outlined),
                        label: const Text('Raindrop에서 북마크 가져오기 & 오프라인 저장'),
                        style: OutlinedButton.styleFrom(
                          foregroundColor: const Color(0xFF0A84FF),
                          side: const BorderSide(color: Color(0xFF0A84FF)),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),

            // 아카이브 내역
            const Text(
              '내 아카이브 내역',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            _isLoadingArchives
                ? const Padding(
                    padding: EdgeInsets.symmetric(vertical: 24),
                    child: Center(
                      child: Text(
                        '아카이브 내역을 불러오는 중...',
                        style: TextStyle(color: Colors.grey),
                      ),
                    ),
                  )
                : _archives.isEmpty
                ? const Padding(
                    padding: EdgeInsets.symmetric(vertical: 32),
                    child: Center(
                      child: Text(
                        '저장된 아카이브가 없습니다.\n다른 앱에서 링크를 공유해보세요!',
                        textAlign: TextAlign.center,
                        style: TextStyle(color: Colors.grey),
                      ),
                    ),
                  )
                : ListView.builder(
                    shrinkWrap: true,
                    physics: const NeverScrollableScrollPhysics(),
                    itemCount: _archives.length,
                    itemBuilder: (context, index) {
                      final item = _archives[index];
                      final title = item['title']?.toString() ?? 'Untitled';
                      final url = item['url']?.toString() ?? '';
                      final provider =
                          item['storage_provider']?.toString() ?? '';
                      final id = item['id']?.toString() ?? '';
                      final isScreenshot = url.startsWith('screenshot://');
                      final isOffline = _cachedArchiveIds.contains(id);

                      return Card(
                        margin: const EdgeInsets.symmetric(vertical: 6),
                        child: ListTile(
                          leading: Stack(
                            alignment: Alignment.bottomRight,
                            children: [
                              Icon(
                                isScreenshot
                                    ? Icons.screenshot_monitor
                                    : Icons.language,
                                color: const Color(0xFF0A84FF),
                                size: 28,
                              ),
                              if (isOffline)
                                Container(
                                  padding: const EdgeInsets.all(1.5),
                                  decoration: const BoxDecoration(
                                    color: Colors.greenAccent,
                                    shape: BoxShape.circle,
                                  ),
                                  child: const Icon(
                                    Icons.check,
                                    size: 9,
                                    color: Colors.black,
                                  ),
                                ),
                            ],
                          ),
                          title: Text(
                            title,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                          subtitle: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                url,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontSize: 12,
                                  color: Colors.grey,
                                ),
                              ),
                              const SizedBox(height: 4),
                              Row(
                                children: [
                                  Text(
                                    '저장소: ${provider.toUpperCase()}',
                                    style: const TextStyle(
                                      fontSize: 11,
                                      color: Color(0xFF0A84FF),
                                    ),
                                  ),
                                  const SizedBox(width: 8),
                                  Container(
                                    padding: const EdgeInsets.symmetric(
                                      horizontal: 6,
                                      vertical: 2,
                                    ),
                                    decoration: BoxDecoration(
                                      color: isOffline
                                          ? Colors.green.withValues(alpha: 0.15)
                                          : Colors.grey.withValues(alpha: 0.15),
                                      borderRadius: BorderRadius.circular(4),
                                    ),
                                    child: Text(
                                      isOffline ? '💾 오프라인 저장됨' : '☁️ 클라우드',
                                      style: TextStyle(
                                        fontSize: 10,
                                        color: isOffline
                                            ? Colors.greenAccent
                                            : Colors.white60,
                                        fontWeight: FontWeight.w500,
                                      ),
                                    ),
                                  ),
                                ],
                              ),
                            ],
                          ),
                          trailing: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              IconButton(
                                icon: Icon(
                                  isOffline
                                      ? Icons.offline_pin
                                      : Icons.download_for_offline_outlined,
                                  color: isOffline
                                      ? Colors.greenAccent
                                      : Colors.white54,
                                  size: 22,
                                ),
                                tooltip: isOffline
                                    ? '오프라인 사본 삭제'
                                    : '오프라인으로 다운로드',
                                onPressed: () => _toggleItemOffline(item),
                              ),
                              const Icon(
                                Icons.chevron_right,
                                color: Colors.white30,
                              ),
                            ],
                          ),
                          onTap: () async {
                            if (isScreenshot) {
                              await Navigator.of(context).push(
                                MaterialPageRoute(
                                  builder: (_) => ScreenshotArchiveViewer(
                                    archiveId: id,
                                    title: title,
                                  ),
                                ),
                              );
                            } else {
                              await Navigator.of(context).push(
                                MaterialPageRoute(
                                  builder: (_) => InAppArchiveViewer(
                                    archiveId: id,
                                    title: title,
                                    originalUrl: url,
                                    backendUrl: getBackendUrl(),
                                  ),
                                ),
                              );
                            }
                            _refreshOfflineStatus();
                          },
                        ),
                      );
                    },
                  ),
          ],
        ),
      ),
    );
  }
}
