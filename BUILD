# TODO(b/198224074): auto-generate this file using bp2build.

java_library(
    name = "guava-android-host",
    srcs = glob(["android/guava/src/**/*.java"]),
    visibility = ["//visibility:public"],
    deps = [
        ":guava-android-annotation-stubs",
        ":guava-both",
        "//external/error_prone:error_prone_annotations",
        "//external/jsr305",
    ],
)

java_library(
    name = "guava-android-annotation-stubs",
    srcs = glob(["android-annotation-stubs/src/**/*.java"]),
)

java_library(
    name = "guava-both",
    srcs = glob(["futures/failureaccess/**/*.java"]),
    deps = [
        ":guava-android-annotation-stubs",
        "//external/error_prone:error_prone_annotations",
        "//external/jsr305",
    ],
)

java_library(
    name = "guava-jre",
    srcs = glob(["guava/src/**/*.java"]),
    visibility = ["//visibility:public"],
    deps = [
        ":guava-android-annotation-stubs",
        ":guava-both",
        "//external/error_prone:error_prone_annotations",
        "//external/jsr305",
    ],
)