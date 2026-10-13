#!/usr/bin/env python3
"""Copy this package into an existing candidate mirror checkout, without publishing."""
import argparse
from pathlib import Path
import shutil

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("destination", type=Path)
args = parser.parse_args()
source = Path(__file__).resolve().parents[1]
destination = args.destination.resolve()
assert (destination / ".git").exists(), "Use the dedicated mirror checkout"
assert source != destination and source not in destination.parents
for name in ("README.md", "build.gradle.kts", "consumer-rules.pro", "gradle.properties", "gradlew", "jitpack.yml"):
    shutil.copy2(source / name, destination / name)
for name in ("src", "gradle", "scripts", "consumer"):
    shutil.copytree(source / name, destination / name, dirs_exist_ok=True,
                    ignore=shutil.ignore_patterns("build", ".gradle", "__pycache__", "local.properties"))
build = destination / "build.gradle.kts"
text = build.read_text()
assert text.count('api("io.vedika:vedika-android-sdk:1.1.0")') == 1
build.write_text(text.replace('api("io.vedika:vedika-android-sdk:1.1.0")',
                             'api("com.github.vedika-io:vedika-sdk-android:1.1.0")')
                 .replace('https://github.com/vedika-io/vedika"', 'https://github.com/vedika-io/vedika-sdk-android-ar"'))
(destination / "settings.gradle.kts").write_text('''pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } }
}
rootProject.name = "vedika-android-ar"
''')
fixtures = source.parent / "fixtures" / "vastu-room-capture"
assert len(list(fixtures.glob("*.json"))) == 7, "All shared capture fixtures must be exported"
for fixture in fixtures.glob("*.json"):
    target = destination / "src/test/resources/vastu-room-capture" / fixture.name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(fixture, target)
workflow = destination / ".github/workflows/android.yml"
workflow.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(source / "ci/android.yml", workflow)
assert (destination / "jitpack.yml").is_file(), "JitPack reads the mirror root configuration"
print("Candidate exported; no push, merge, tag or release performed.")
