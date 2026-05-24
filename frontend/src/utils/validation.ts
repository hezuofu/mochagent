import * as yup from 'yup';
import type { Rule } from 'antd/es/form';

/**
 * 将 Yup string schema 的校验规则提取为 Ant Design Form.Item Rule[]。
 * 通过 schema.describe() 内省 Yup 的 test 链，自动转换为对应的 Ant Design rules。
 */
function toAntdRules(schema: yup.StringSchema): Rule[] {
  const desc = schema.describe();
  const rules: Rule[] = [];

  if (desc.type !== 'string') return rules;

  const tests = (desc as any).tests as Array<{
    name: string;
    params?: Record<string, unknown>;
  }> | undefined;

  if (!tests) return rules;

  for (const test of tests) {
    switch (test.name) {
      case 'required':
        rules.push({
          required: true,
          message: (test.params?.message as string) || '此项为必填',
        });
        break;
      case 'min':
        rules.push({
          min: Number(test.params?.min),
          message: (test.params?.message as string) || `至少 ${test.params?.min} 个字符`,
        });
        break;
      case 'max':
        rules.push({
          max: Number(test.params?.max),
          message: (test.params?.message as string) || `最多 ${test.params?.max} 个字符`,
        });
        break;
      case 'matches':
        rules.push({
          pattern: test.params?.regex as RegExp,
          message: (test.params?.message as string) || '格式不正确',
        });
        break;
      case 'email':
        rules.push({
          type: 'email',
          message: (test.params?.message as string) || '邮箱格式不正确',
        });
        break;
      case 'url':
        rules.push({
          type: 'url',
          message: (test.params?.message as string) || 'URL格式不正确',
        });
        break;
      default:
        break;
    }
  }

  return rules;
}

// ── Yup schemas ──

const schemas = {
  username: yup.string()
    .required('请输入用户名')
    .matches(/^[a-zA-Z][a-zA-Z0-9_]{3,19}$/, '4-20位，字母开头，仅含字母数字下划线'),

  password: yup.string()
    .required('请输入密码')
    .min(6, '密码至少6位')
    .max(32, '密码最多32位'),

  email: yup.string().required('请输入邮箱').email('邮箱格式不正确'),
  optionalEmail: yup.string().email('邮箱格式不正确'),

  phone: yup.string()
    .required('请输入手机号')
    .matches(/^1[3-9]\d{9}$/, '手机号格式不正确'),

  optionalPhone: yup.string()
    .matches(/^1[3-9]\d{9}$/, { message: '手机号格式不正确', excludeEmptyString: true }),

  url: yup.string().required('请输入URL').url('URL格式不正确'),
  code: yup.string()
    .required('请输入编码')
    .matches(/^[A-Z][A-Z0-9_]{0,49}$/, '大写字母开头，仅含大写字母数字下划线'),

  semver: yup.string()
    .matches(/^\d+\.\d+\.\d+$/, '请输入正确版本号，如 1.0.0'),
};

// ── 向后兼容的 rules 对象（与旧 validators.ts 接口一致）──

export const rules = {
  /** 纯必填（不依赖 Yup schema） */
  required(msg = '此项为必填'): Rule[] {
    return [{ required: true, message: msg }];
  },
  username: (): Rule[] => toAntdRules(schemas.username),
  password: (): Rule[] => toAntdRules(schemas.password),
  email: (): Rule[] => toAntdRules(schemas.email),
  optionalEmail: (): Rule[] => toAntdRules(schemas.optionalEmail),
  phone: (): Rule[] => toAntdRules(schemas.phone),
  optionalPhone: (): Rule[] => toAntdRules(schemas.optionalPhone),
  url: (): Rule[] => toAntdRules(schemas.url),
  name: (maxLen = 50): Rule[] =>
    toAntdRules(yup.string().required('请输入名称').max(maxLen, `不得超过${maxLen}个字符`)),
  code: (): Rule[] => toAntdRules(schemas.code),
  semver: (): Rule[] => toAntdRules(schemas.semver),
};

// ── 命令式校验（非 Form 场景）──

export const validate = {
  notEmpty: (v: string) => v.trim().length > 0,
  email: (v: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v),
  phone: (v: string) => /^1[3-9]\d{9}$/.test(v),
  semver: (v: string) => /^\d+\.\d+\.\d+$/.test(v),
};

// ── 全局中文校验消息模板 ──

export const validateMessages = {
  required: '${label} 为必填项',
  types: {
    email: '${label} 格式不正确',
    url: '${label} 格式不正确',
    number: '${label} 必须为数字',
  },
  number: {
    min: '${label} 不得小于 ${min}',
    max: '${label} 不得大于 ${max}',
  },
  string: {
    min: '${label} 至少 ${min} 个字符',
    max: '${label} 最多 ${max} 个字符',
    len: '${label} 必须为 ${len} 个字符',
  },
  pattern: {
    mismatch: '${label} 格式不正确',
  },
};

// ── 导出 Yup schemas 供需要完整 schema 校验的场景使用 ──
export { schemas };
