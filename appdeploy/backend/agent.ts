import { router, json, error } from '@appdeploy/sdk';
import { secrets } from '@appdeploy/sdk';

type PlanBody = {
  instruction?: string;
  screenshot?: { data?: string; mimeType?: string };
};

const actionSchema = {
  type: 'OBJECT',
  properties: {
    action: {
      type: 'STRING',
      enum: ['tap', 'swipe', 'type', 'launch_app', 'back', 'wait', 'ask_user', 'done'],
    },
    explanation: { type: 'STRING' },
    x: { type: 'INTEGER' },
    y: { type: 'INTEGER' },
    endX: { type: 'INTEGER' },
    endY: { type: 'INTEGER' },
    text: { type: 'STRING' },
    app: { type: 'STRING' },
    question: { type: 'STRING' },
    requiresConfirmation: { type: 'BOOLEAN' },
  },
  required: ['action', 'explanation', 'requiresConfirmation'],
};

export const agentRoutes = {
  'GET /api/agent/status': [
    async () => {
      const names = await secrets.listSecretNames();
      return json({
        provider: 'Google Gemini',
        model: 'gemini-2.5-flash',
        configured: names.includes('GEMINI_API_KEY'),
      });
    },
  ],
  'POST /api/agent/plan': [
    async ({ body }) => {
      const input = (body || {}) as PlanBody;
      const instruction = input.instruction?.trim();
      if (!instruction) return error('A task instruction is required.', 400);
      if (instruction.length > 1200) return error('Instruction is too long.', 400);

      const data = input.screenshot?.data;
      const mimeType = input.screenshot?.mimeType || 'image/jpeg';
      if (!data || !['image/jpeg', 'image/png', 'image/webp'].includes(mimeType)) {
        return error('A current user-authorized screen image is required.', 400);
      }
      if (data.length > 5000000) return error('Screen image is too large.', 413);

      let key: string;
      try {
        key = await secrets.readSecret('GEMINI_API_KEY');
      } catch {
        return error('Gemini is not configured in AppDeploy Secrets.', 503);
      }

      const prompt = [
        'You are Astra, a general-purpose Android UI task planner.',
        'Inspect the screenshot and choose exactly one next action for the Android AccessibilityService.',
        'Coordinates x,y,endX,endY are normalized from 0 to 1000.',
        'Use only visible ordinary UI actions. Never invent screen content or claim an action happened.',
        'If a person, account, amount, recipient or destination is ambiguous, choose ask_user and explain what is missing.',
        'For a consequential final step such as sending a message, placing a call, submitting a bank transfer, paying a bill, making a purchase, deleting data or publishing publicly: return the actual UI action with requiresConfirmation=true and put a clear review question in question. The Android app will pause before performing it and wait for the user to approve that exact pending action.',
        'Never request, read, store, type, transmit or infer a password, PIN, OTP, card security code or authentication secret. If such a screen appears, choose ask_user and tell the user to handle authentication privately in the target app.',
        'Do not claim that a transfer, payment, message or other task has completed unless the visible screen proves it.',
        'If complete choose done; if loading choose wait. Return JSON matching the schema.',
        'User instruction: ' + instruction,
      ].join('\\n');

      const response = await fetch(
        'https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=' + encodeURIComponent(key),
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            contents: [{
              role: 'user',
              parts: [
                { text: prompt },
                { inline_data: { mime_type: mimeType, data } },
              ],
            }],
            generationConfig: {
              temperature: 0.2,
              responseMimeType: 'application/json',
              responseSchema: actionSchema,
            },
          }),
        }
      );

      if (!response.ok) {
        return error(
          response.status === 429
            ? 'Gemini free-tier quota reached. Try again later.'
            : 'Gemini request failed (' + response.status + '). Check model access and API configuration.',
          response.status === 429 ? 429 : 502
        );
      }

      const payload = (await response.json()) as {
        candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }>;
      };
      const output = payload.candidates?.[0]?.content?.parts?.map(part => part.text || '').join('') || '';
      try {
        return json({ plan: JSON.parse(output), provider: 'Google Gemini', model: 'gemini-2.5-flash' });
      } catch {
        return error('Gemini returned an unreadable plan. Please try again.', 502);
      }
    },
  ],
};

export const handler = router(agentRoutes);
