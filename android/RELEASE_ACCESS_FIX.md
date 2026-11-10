# Release IllegalAccessError 분석·최소 수정

## 최신 Release 검증 — 2026-11-07 반영

소유자가 확인하여 제공한 최신 검증 결과를 반영했다. 이번 문서 보완에서 서명·설치·앱 실행을 다시 수행한 것은 아니다.

| 항목 | 최신 확인 결과 |
|---|---|
| Release APK | 소유자 서명 성공 |
| 실행 환경 | Pleos API 34 에뮬레이터 설치·실행 성공 |
| R8 접근성 | DEX 불법 상속 관계 0건 |
| Release DEMO | 여행 진행·체류시간 변경·귀가·여행 종료·로컬 기록 저장 검증 완료 |
| 실제 NaviHelper | 목적지 경로 안내는 아직 미검증. 추천 장소 내비 시작 비활성 유지 |
| 실제 공급자 | 위 DEMO 결과는 실제 OpenAI·카카오·내비게이션 전체 연동 검증을 뜻하지 않음 |

서명된 APK와 대응 mapping·metadata는 `android/TripbackApp/app/release/`에 로컬 보존하며 소스 커밋에서 제외한다.
기존 unsigned APK 설치 실패와 R8 진단 기록은 당시 산출물에 대한 검증 이력으로 유지한다.

## 이전 검증 이력 — 2026-11-05

이하 기존 본문은 당시 unsigned 빌드와 진단 결과를 기록한 이력이다. 재서명·전체 실행 확인이 필요하다는 당시 판정은 위 최신 결과와 구분한다.

2026-11-05. 기존 feature/android-ai-recommendation-ui 및 양쪽 미커밋 변경 보존.
기존 owner-signed APK/mapping과 설치된 Release 앱을 덮어쓰거나 삭제하지 않았다.
키/비밀번호/환경 파일 열람 없음. 새 브랜치·commit/push/PR/병합 없음.
외부 공급자/실제 내비 경로 요청 없음.

## 정확한 원인

사용자가 제공한 APK: C:/dev/Tripback-Release-Output/release/app-release.apk.
이 폴더의 mapping.txt ID는 `e798dd8dc03b9e1fe702998b72b2b2de37e642d13076c7796414551f63b72c5b`이며,
실제 AndroidRuntime 스택의 r8-map-id와 일치했다.
프로젝트 build/outputs의 최신 mapping은 ID가 달라 원본 분석에 사용하지 않았다.

| 원본 클래스 | 충돌 APK 이름 |
|---|---|
| kotlin.collections.CollectionsKt__IterablesKt | kotlin.collections.g |
| kotlin.collections.CollectionsKt__IteratorsKt | fi |
| androidx.startup.AppInitializer | ba |
| androidx.startup.StartupException | f61 |

SDK Build Tools의 Retrace 8.10.9-dev로 원본 mapping 및 기록된 실제 스택 프레임을 역추적했다:

```text
androidx.lifecycle.ProcessLifecycleInitializer.dependencies(ProcessLifecycleInitializer.kt:39)
androidx.startup.AppInitializer.doInitialize(AppInitializer.java:168)
androidx.startup.AppInitializer.discoverAndInitialize(AppInitializer.java:238)
androidx.startup.AppInitializer.discoverAndInitialize(AppInitializer.java:206)
androidx.startup.InitializationProvider.onCreate(InitializationProvider.java:45)
```

실제 DEX class_def에서 fi는 access_flags=0x401(PUBLIC|ABSTRACT), superclass=kotlin.collections.g였다.
부모 g는 access_flags=0x0(package-private)로 원래 kotlin.collections 패키지에 남았다.
R8이 자식만 기본 패키지로 이동시켜 package-private 부모를 다른 패키지에서 상속하게 만들었다.
ART가 이 불법 접근을 거부하므로 MainActivity 이전 Startup provider 설치 단계에서 충돌한다.
Lifecycle/Startup은 최초 로드 경로이며, SDK/차량 권한이나 API 키가 원인이 아니다.

Kotlin stdlib 원본 javap에서도 CollectionsKt 구현 클래스들은 package-private이며 같은 패키지에 있는
파사드 상속 체인임을 확인했다. 원래 정상인 체인을 R8 출력이 잘못 분리한 것이 직접 원인이다.
특정 upstream 이슈 번호나 모든 R8 버전에 동일한 결함이 있다고 주장하지 않는다.

## 확인한 빌드·호환성

- AGP 9.4.1, mapping compiler_version R8 9.4.24, min API 28.
- release optimization.enable=true, packageScope={androidx.**, kotlin.**, kotlinx.**} 유지.
- AGP의 기본 proguard-android-optimize.txt 및 -allowaccessmodification이 이미 적용돼 있었다.
- proguard-rules.pro는 없었다. 기존 src/main/keepRules/rules.keep는 템플릿이며 커스텀 규칙이 없었다.
- 실제 releaseRuntimeClasspath: kotlin-stdlib 2.2.10, Lifecycle 모듈 2.9.4, Startup runtime 1.1.1.
  카탈로그의 Lifecycle 2.6.1 요청값과 최종 2.9.4 해석을 구분했다.
- 여러 Kotlin 요청 버전은 최종 2.2.10으로 수렴한다. 이번 증거는 의존성 버전 충돌보다 생성 DEX 접근 오류를 직접 가리킨다.
  불필요한 stdlib/Lifecycle/Startup/AGP 버전 변경을 하지 않았다.
- 공식 문서에서 packageScope는 experimental이며, AGP 9.1 이후 기본 재패키징과 keep modifier의 동작을 확인했다.
  기존 점진 최적화 설정을 전면 교체하지 않았다.

근거:
[packageScope](https://developer.android.com/topic/performance/app-optimization/optimize-specified-packages),
[AGP/R8 동작](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization),
[keep modifiers](https://developer.android.com/topic/performance/app-optimization/add-keep-rules).

## 최소 수정과 범위

신규 `app/src/release/keepRules/kotlin-collections-access.keep`:

```proguard
-keep,allowshrinking,allowoptimization class kotlin.collections.CollectionsKt*
```

이 파사드 계열의 이름/패키지만 보존한다. 사용되지 않는 코드는 제거할 수 있고 최적화는 허용한다.
Kotlin 전체·Lifecycle·Startup을 통째로 keep하지 않고, 전역 dontoptimize/dontobfuscate/dontrepackage도 추가하지 않았다.
AGP 9.4.1의 keepRules 소스셋이 규칙을 자동 수집하므로 build.gradle.kts를 수정할 필요가 없었다.
실제 생성 configuration.txt에서 이 파일/규칙이 포함된 것을 확인했다.

처음에는 IteratorsKt 한 클래스만 보존했지만 다음 자식 CollectionsKt___CollectionsJvmKt가 기본 패키지로 이동했다.
검사에서 같은 불법 접근이 다음 경계로 이동함을 확인하여 같은 파사드 계열 전체로만 범위를 넓혔다.
최종 DEX에서 CollectionsKt 계열은 같은 패키지에 남고, 미사용 파사드/합성 클래스는 여전히 제거됐다.
비축소 빌드로 우회할 필요가 없었으며 난독화 전체를 끈 빌드는 만들지 않았다.

추가한 `tools/check_dex_superclass_access.py`는 APK 내 모든 DEX의 클래스/부모 access flag를 읽어
서로 다른 패키지의 비공개 부모 상속을 검사한다. 플랫폼 외부 클래스·메서드 접근·전체 ART verifier를 대신하지 않는다.

## 전후 검증

### APK 구조

```powershell
python tools/check_dex_superclass_access.py C:/dev/Tripback-Release-Output/release/app-release.apk
# exit 1: Lfi; -> Lkotlin/collections/g; (parent 0x0)
python tools/check_dex_superclass_access.py android/TripbackApp/app/build/outputs/apk/release/app-release-unsigned.apk
# exit 0: illegal_superclass_edges=[]
```

원본 APK 3388 클래스에서 불법 상속 1건, 수정 APK 3750 클래스에서 0건.
규칙 범위는 한 파사드 계열이나 최적화/클래스 병합 결과가 달라 클래스 수와 크기도 달라졌다.
크기가 같아야 수정됐다는 기준을 사용하지 않았다.

### 실제 API 34 ART 비교 — 소유자 서명 불필요

`emulator-5554`, Pleos_Connect_-_Tripback_Release_Test.
진단용 ReleaseClassProbe.java를 javac --release 8 + D8 --min-api 28로 별도 DEX로 만들었다.
/data/local/tmp에 원본/수정 APK와 진단 DEX만 push하고 dalvikvm의 별도 클래스 로더에서 검사했다.
앱을 설치/재서명/실행하지 않았고 Context/SDK 경로/앱 데이터에 접근하지 않았다.

```text
before: Class.forName("fi") -> IllegalAccessError (동일 오류), exit 1
after: Class.forName("kotlin.collections.CollectionsKt__IteratorsKt") -> 정상
PARENT_CHAIN CollectionsKt__IteratorsKt -> CollectionsKt__IteratorsJVMKt
 -> CollectionsKt__IterablesKt -> CollectionsKt__CollectionsKt
 -> CollectionsKt__CollectionsJVMKt -> java.lang.Object
PROCESS_LIFECYCLE_DEPENDENCIES size=0
PROBE_PASSED, exit 0
```

수정 APK로 ProcessLifecycleInitializer를 생성하고 List 반환/무인자 의존성 메서드도 호출했다.
이는 충돌 지점의 실제 ART 검증이며 InitializationProvider attach/MainActivity/화면 전체 실행 성공은 아니다.
소유자 재서명 후 전체 앱 실행 검증은 아직 필요하다.

### 회귀·빌드

```powershell
# android/TripbackApp, JAVA_HOME=Android Studio JBR, ANDROID_HOME=설치 SDK
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease compileReleaseKotlin --offline --no-configuration-cache
.\gradlew.bat :app:testDebugUnitTest --rerun --offline --no-configuration-cache
.\gradlew.bat assembleRelease compileReleaseKotlin --offline --no-configuration-cache
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w com.tripback.app.test/androidx.test.runner.AndroidJUnitRunner
git diff --check
```

- 단위 114 성공, 실패/skip 0. 마지막에는 test task만 --rerun하여 UP-TO-DATE 재사용 없이 실행했다.
- 기존 전체 계측 `OK (16 tests)`: 15 성공 + opt-in loopback 1 skipped.
- 계측 AVD는 기존 Pleos_Connect_v2.1.0/emulator-5556/API 34. 별도로 실행했으며 Release Test의 설치 앱을 보존했다.
- 마지막 수정은 Release 전용 keep 규칙이므로 Debug 산출물·기존 UI 테스트 코드를 변경하지 않았다.
- Lint vital/R8 포함 최적화 Release 빌드, Release Kotlin, Debug/계측 APK 빌드 성공.
- Release manifest cleartext=false, 실제 패키지 network base-config=false, loopback 예외 없음, DO 추가 없음.
- Debug 전용 메뉴 조건·HTTP 보안·CarUxRestrictions·실제 추천 내비 비활성을 수정하지 않았다.
- SDK/공급자/차량 이벤트 기능을 새로 활성화하지 않았다.

## 산출물 및 재서명

프로젝트 기준 `android/TripbackApp/app/build/outputs/release-access-evidence/`:

- fixed/app-release-unsigned.apk: 수정된 최적화 Release, 3518741 bytes.
- fixed/mapping.txt, fixed/configuration.txt: 이 APK와 일치하는 R8 결과.
- fixed/artifact-hashes.json: APK/mapping/config SHA256.
- before-mapping.txt, before-configuration.txt: 빌드 이전 프로젝트 산출물 보존. 원본 충돌 분석 mapping은 사용자 Release Output 폴더의 e798... 파일이다.
- art-before.txt, art-after.txt, dex-after.json: 실제 검사 결과.
- ReleaseClassProbe.java, ReleaseClassProbe.class, classes.dex, crash-frames.txt: 진단 자료.

수정 APK SHA256:
`ef65f8301c9950ccb612a30c1d86e96b8f659894113c6dfce855660d6e5c7c1d`.

수정 APK는 unsigned이며 소유자 키로 재서명해야 한다. 키 파일/암호를 열거나 임의 서명하지 않았다.
기존 C:/dev/Tripback-Release-Output/release/app-release.apk는 충돌하는 이전 파일 그대로이다.
서명 후 apksigner 검증, 기존 Release Test AVD에 소유자가 업데이트 설치, 초기화/화면 전환과 전체 시연을 재확인해야 한다.
새 오류가 나오면 새 APK에 맞는 fixed/mapping.txt와 함께 분석한다. 예전 mapping을 사용하지 않는다.

이번 수정 파일은 Release keep 규칙, DEX 회귀 검사 도구, 이 보고서 3개이다.
