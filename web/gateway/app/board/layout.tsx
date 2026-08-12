'use client';

import React, { type ReactNode } from 'react';
import { AuthProvider } from '@/lib/auth-context';

export default function BoardLayout({ children }: { readonly children: ReactNode }): React.ReactElement {
  return <AuthProvider>{children}</AuthProvider>;
}
