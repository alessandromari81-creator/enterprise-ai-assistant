<?php

namespace App\Http\Controllers;

use App\Http\Requests\ChatRequest;
use App\Services\Chat\ChatService;
use Illuminate\Http\JsonResponse;

/** POST /api/chat. Il controller resta sottile: la logica è in ChatService. */
class ChatController extends Controller
{
    public function store(ChatRequest $request, ChatService $chat): JsonResponse
    {
        $result = $chat->reply(
            $request->validated('message'),
            $request->validated('conversationId'),
            $request->validated('documentIds'),
        );

        return response()->json($result);
    }
}
