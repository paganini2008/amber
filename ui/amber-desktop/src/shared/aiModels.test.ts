import { describe, expect, it } from 'vitest'
import { acceptsTemperature, isChatModel, isOpenAiHost, modelOptions, OLLAMA_PRESETS, OPENAI_PRESETS } from './aiModels'

describe('model presets', () => {
  it('keep ids unique and list recommended ones', () => {
    for (const list of [OPENAI_PRESETS, OLLAMA_PRESETS]) {
      expect(new Set(list.map((p) => p.id)).size).toBe(list.length)
      expect(list.some((p) => p.recommended)).toBe(true)
      expect(list.every((p) => isChatModel(p.id))).toBe(true)
    }
  })
})

describe('isChatModel', () => {
  it('drops embedding, speech, image, moderation and legacy models', () => {
    for (const id of ['text-embedding-3-large', 'nomic-embed-text:latest', 'mxbai-embed-large', 'bge-m3:latest', 'tts-1-hd', 'whisper-1', 'gpt-5-transcribe', 'dall-e-3', 'gpt-image-1', 'omni-moderation-latest', 'davinci-002', 'gpt-realtime-2.1', 'gpt-audio-1.5', 'gpt-live-1', 'sora-2', '']) {
      expect(isChatModel(id)).toBe(false)
    }
    for (const id of ['gpt-6.1-sol', 'gpt-6-luna', 'gpt-5.5', 'chat-latest', 'qwen2.5-coder:7b', 'deepseek-chat', 'qwen-plus', 'llama3.1:8b']) expect(isChatModel(id)).toBe(true)
  })
})

describe('modelOptions', () => {
  it('shows recommended presets, then what the provider lists, then the other presets', () => {
    const loaded = ['gpt-6-luna', 'gpt-6.1-sol', 'gpt-5.5', 'gpt-5.3-chat-latest', 'text-embedding-3-small']
    const opts = modelOptions(OPENAI_PRESETS, loaded)
    expect(opts.map((o) => [o.group, o.id, o.available])).toEqual([
      ['recommended', 'gpt-6.1-sol', true],
      ['recommended', 'gpt-6.1-terra', false],
      ['recommended', 'gpt-6-luna', true],
      ['available', 'gpt-5.3-chat-latest', true],
      ['more', 'gpt-6-astra', false],
      ['more', 'gpt-5.6-sol', false],
      ['more', 'gpt-5.6-terra', false],
      ['more', 'gpt-5.6-luna', false],
      ['more', 'gpt-5.5', true],
      ['more', 'gpt-5.4-mini', false]
    ])
  })
  it('marks availability unknown before loading, and shows only loaded ids for other providers', () => {
    expect(modelOptions(OLLAMA_PRESETS, null).every((o) => o.available === null)).toBe(true)
    expect(modelOptions(OPENAI_PRESETS, ['deepseek-chat', 'deepseek-reasoner'], false).map((o) => [o.group, o.id])).toEqual([
      ['available', 'deepseek-chat'],
      ['available', 'deepseek-reasoner']
    ])
    expect(modelOptions(OPENAI_PRESETS, null, false)).toEqual([])
    // Ollama: installed presets are marked, embedding models never offered.
    const ollama = modelOptions(OLLAMA_PRESETS, ['qwen2.5-coder:7b', 'nomic-embed-text:latest', 'mistral:7b'])
    expect(ollama.find((o) => o.id === 'qwen2.5-coder:7b')?.available).toBe(true)
    expect(ollama.find((o) => o.id === 'qwen3:8b')?.available).toBe(false)
    expect(ollama.some((o) => o.id.includes('embed'))).toBe(false)
    expect(ollama.find((o) => o.id === 'mistral:7b')?.group).toBe('available')
  })
  it('knows the OpenAI host and the reasoning models', () => {
    expect(isOpenAiHost('https://api.openai.com/v1')).toBe(true)
    expect(isOpenAiHost('https://dashscope.aliyuncs.com/compatible-mode/v1')).toBe(false)
    expect(isOpenAiHost('not a url')).toBe(false)
    expect(acceptsTemperature('o3-mini')).toBe(false)
        for (const id of ['gpt-5', 'gpt-5.5', 'gpt-5.6-terra', 'gpt-6-astra', 'GPT-6.1-sol']) expect(acceptsTemperature(id)).toBe(false)
    expect(acceptsTemperature('gpt-5.3-chat-latest')).toBe(true)
    expect(acceptsTemperature('deepseek-chat')).toBe(true)
  })
})
