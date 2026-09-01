#!/usr/bin/bash

# update fcitx5-rime
echo "updating fcitx5-rime"
pushd plugin/rime/src/main/cpp/fcitx5-rime
git remote add gh https://github.com/fxliang/fcitx5-rime.git || git remote set-url gh https://github.com/fxliang/fcitx5-rime.git
git fetch -v gh master
git checkout gh/master
popd
sed -i 's|/fcitx/|/fxliang/|g' plugin/rime/licenses/libraries/fcitx5-rime.json

# apply fcitx5 patch from fcitx5-rime
echo "applying fcitx5 patch"
pushd lib/fcitx5/src/main/cpp/fcitx5
# reset to clean state first
git checkout -- .
git apply ../../../../../../plugin/rime/src/main/cpp/fcitx5-rime/fcitx5-alt-trigger-v4point1.patch || echo "fcitx5 patch already applied or failed"
popd

echo "applying slei pinyin patch"
pushd lib/fcitx5-chinese-addons/src/main/cpp/fcitx5-chinese-addons
if git apply --check ../slei-pinyin.patch; then
    git apply ../slei-pinyin.patch
elif git apply --reverse --check ../slei-pinyin.patch; then
    echo "slei pinyin patch already applied"
else
    echo "slei pinyin patch does not apply cleanly" >&2
    exit 1
fi
popd

echo "applying slei history weight patch"
pushd lib/libime/src/main/cpp/libime
if git apply --check ../slei-history-weight.patch; then
    git apply ../slei-history-weight.patch
elif git apply --reverse --check ../slei-history-weight.patch; then
    echo "slei history weight patch already applied"
else
    echo "slei history weight patch does not apply cleanly" >&2
    exit 1
fi
popd

# update prebuilt
echo "updating prebuilt"
pushd lib/fcitx5/src/main/cpp/prebuilt
git remote add gh https://github.com/fxliang/prebuilt.git || git remote set-url gh https://github.com/fxliang/prebuilt.git
git fetch -v gh master
git checkout gh/master
popd
