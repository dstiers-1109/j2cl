load("//build_defs:rules.bzl", "j2cl_library")
load("//tools/javatests/com/google/j2cl/tools/rta:rta_test.bzl", "rta_test")

package(
    licenses = ["notice"],  # Apache 2.0
)

j2cl_library(
    name = "staticproperties_library",
    srcs = [
        "StaticPropertiesAccessor.java",
    ],
    deps = ["//third_party:gwt-jsinterop-annotations-j2cl"],
)

rta_test(
    name = "staticproperties",
    targets = [":staticproperties_library"],
    unused_members_golden_file = "unused_members.txt",
    unused_types_golden_file = "unused_types.txt",
)
