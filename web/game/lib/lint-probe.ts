import { useState } from 'react';

// 임시 적색 탐침: 훅을 조건부로 부른다(react-hooks/rules-of-hooks 오류). CI lint 단계가 잡는지 본 뒤 지운다.
export function LintProbe(flag: boolean) {
  if (flag) {
    useState(0);
  }
  return null;
}
