<?php

use App\Exceptions\ApiProblem;
use Illuminate\Database\Eloquent\ModelNotFoundException;
use Illuminate\Foundation\Application;
use Illuminate\Foundation\Configuration\Exceptions;
use Illuminate\Foundation\Configuration\Middleware;
use Illuminate\Http\Exceptions\PostTooLargeException;
use Illuminate\Http\Request;
use Illuminate\Validation\ValidationException;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Exception\HttpExceptionInterface;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;

return Application::configure(basePath: dirname(__DIR__))
    ->withRouting(
        // Solo API: nessuna rotta web. Il prefisso "/api" è aggiunto automaticamente.
        api: __DIR__.'/../routes/api.php',
        commands: __DIR__.'/../routes/console.php',
        health: '/up',
    )
    ->withMiddleware(function (Middleware $middleware): void {
        //
    })
    ->withExceptions(function (Exceptions $exceptions): void {
        // Su /api/* ogni errore deve essere JSON in formato RFC 9457 (application/problem+json),
        // identico a quello del backend Spring Boot. ApiProblem (errori di dominio) si
        // renderizza da sola; qui si convertono gli errori del framework.
        $isApi = fn (Request $request) => $request->is('api/*');

        $exceptions->shouldRenderJsonWhen(fn (Request $request) => $isApi($request) || $request->expectsJson());

        // Validazione fallita -> 400 (non il 422 predefinito di Laravel) con errors[{field, message}].
        $exceptions->render(function (ValidationException $e, Request $request) use ($isApi) {
            if (! $isApi($request)) {
                return null;
            }

            $errors = [];
            foreach ($e->errors() as $field => $messages) {
                foreach ($messages as $message) {
                    $errors[] = ['field' => $field, 'message' => $message];
                }
            }

            return ApiProblem::validation($errors)->render();
        });

        // Body oltre post_max_size -> 413.
        $exceptions->render(function (PostTooLargeException $e, Request $request) use ($isApi) {
            return $isApi($request) ? ApiProblem::fileTooLarge()->render() : null;
        });

        // Rotta o risorsa inesistente -> 404.
        $exceptions->render(function (NotFoundHttpException|ModelNotFoundException $e, Request $request) use ($isApi) {
            return $isApi($request) ? ApiProblem::notFound('Risorsa non trovata.')->render() : null;
        });

        // Altri errori HTTP (405, 429...): stesso formato, status originale.
        $exceptions->render(function (HttpExceptionInterface $e, Request $request) use ($isApi) {
            if (! $isApi($request)) {
                return null;
            }

            $status = $e->getStatusCode();

            return response()->json([
                'type' => 'urn:eaa:error:http-'.$status,
                'title' => Response::$statusTexts[$status] ?? 'Error',
                'status' => $status,
                'detail' => $e->getMessage() !== '' ? $e->getMessage() : (Response::$statusTexts[$status] ?? 'Error'),
            ], $status, ['Content-Type' => 'application/problem+json']);
        });

        // Qualsiasi altra eccezione -> 500 generico. Il dettaglio resta solo nel log: niente stack trace al client.
        $exceptions->render(function (Throwable $e, Request $request) use ($isApi) {
            return $isApi($request) ? ApiProblem::internal()->render() : null;
        });
    })->create();
