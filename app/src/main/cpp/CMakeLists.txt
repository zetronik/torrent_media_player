cmake_minimum_required(VERSION 3.22.1)
project(torrentplayer_fs C)

add_library(torrentplayer_fs SHARED native_fs.c)
# 16 KB page alignment, required for apps targeting Android 15+ on 16 KB devices.
target_link_options(torrentplayer_fs PRIVATE "-Wl,-z,max-page-size=16384")
