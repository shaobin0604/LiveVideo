APP_MODULES  += libffmpeg-prebuilt
APP_MODULES  += librtp-player

APP_OPTIM        := release 
APP_CFLAGS       += -O3

# Build both ARMv5TE and ARMv7-A machine code.
APP_ABI := armeabi armeabi-v7a

