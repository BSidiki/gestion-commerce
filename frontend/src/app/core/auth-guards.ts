import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { AuthState } from './auth-state';

/** Écrans de l'application : il faut un compte propriétaire créé, puis être connecté. */
export const signedIn: CanActivateFn = async () => {
  const auth = inject(AuthState);
  const router = inject(Router);
  try {
    const me = await auth.ensureLoaded();
    if (me.setupRequired) {
      return router.parseUrl('/installation');
    }
    return me.user ? true : router.parseUrl('/connexion');
  } catch {
    // Le service local ne répond pas : l'écran demandé l'expliquera lui-même.
    return true;
  }
};

/** Écrans réservés au propriétaire. À placer après signedIn. */
export const ownerOnly: CanActivateFn = async () => {
  const auth = inject(AuthState);
  const router = inject(Router);
  try {
    // Les gardes d'une même route démarrent ensemble : on attend ici aussi l'état de connexion.
    const me = await auth.ensureLoaded();
    return me.user?.role === 'PROPRIETAIRE' ? true : router.parseUrl('/');
  } catch {
    return true;
  }
};

/** Écrans réservés au propriétaire et à la secrétaire. À placer après signedIn. */
export const managerOnly: CanActivateFn = async () => {
  const auth = inject(AuthState);
  const router = inject(Router);
  try {
    const role = (await auth.ensureLoaded()).user?.role;
    return role === 'PROPRIETAIRE' || role === 'SECRETAIRE' ? true : router.parseUrl('/');
  } catch {
    return true;
  }
};

/** Écrans de connexion et de première installation : inutiles une fois connecté. */
export const signedOut: CanActivateFn = async route => {
  const auth = inject(AuthState);
  const router = inject(Router);
  try {
    const me = await auth.ensureLoaded();
    const wantsSetup = route.routeConfig?.path === 'installation';
    if (me.setupRequired) {
      return wantsSetup ? true : router.parseUrl('/installation');
    }
    if (me.user) {
      return router.parseUrl('/');
    }
    return wantsSetup ? router.parseUrl('/connexion') : true;
  } catch {
    return true;
  }
};

/** Session expirée ou fermée ailleurs : retour à l'écran de connexion. */
export const sessionExpired: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthState);
  const router = inject(Router);
  return next(request).pipe(
    catchError((error: unknown) => {
      const duringSignIn = request.url.startsWith('/api/auth/');
      if (error instanceof HttpErrorResponse && error.status === 401 && !duringSignIn) {
        auth.signedOut();
        router.navigateByUrl('/connexion');
      }
      return throwError(() => error);
    }),
  );
};
