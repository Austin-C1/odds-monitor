#!/usr/bin/env node

/**
 * 获取订单详情脚本
 * 
 * 使用方法:
 *   node scripts/get-order-detail.js <private_key> <order_id>
 * 
 * 参数说明:
 *   private_key: 钱包私钥（用于签名）
 *   order_id: 订单 ID
 * 
 * 示例:
 *   node scripts/get-order-detail.js "0x..." "0x123..."
 */

import { Wallet } from '@ethersproject/wallet';
import { ClobClient } from '@polymarket/clob-client';

// Polymarket CLOB 主机地址
const HOST = 'https://clob.polymarket.com';
const CHAIN_ID = 137; // Polygon 主网

async function getOrderDetail(privateKey, orderId) {
    try {
        console.log('正在初始化钱包...');
        const wallet = new Wallet(privateKey);
        console.log(`钱包地址: ${wallet.address}`);

        console.log('\n正在初始化 ClobClient...');
        const clobClient = new ClobClient(
            HOST,
            CHAIN_ID,
            wallet
        );

        console.log('\n正在获取或创建 API Key...');
        try {
            // 尝试 derive API key（如果已存在）
            const creds = await clobClient.deriveApiKey();
            console.log(`✅ API Key 已获取`);
            console.log(`   API Key: ${creds.key.substring(0, 10)}...`);
            console.log(`   Passphrase: ${creds.passphrase.substring(0, 10)}...`);

            // 使用 creds 初始化一个新的 ClobClient 实例用于 L2 认证
            const authenticatedClient = new ClobClient(
                HOST,
                CHAIN_ID,
                wallet,
                creds
            );

            console.log(`\n正在获取订单详情...`);
            console.log(`   订单 ID: ${orderId}`);

            const orderDetail = await authenticatedClient.getOrder(orderId);

            console.log('\n================ 订单详情 ================');
            console.log(`订单 ID: ${orderDetail.id}`);
            console.log(`状态: ${orderDetail.status}`);
            console.log(`所有者: ${orderDetail.owner}`);
            console.log(`Maker 地址: ${orderDetail.maker_address}`);
            console.log(`市场 ID: ${orderDetail.market}`);
            console.log(`资产 ID: ${orderDetail.asset_id}`);
            console.log(`方向: ${orderDetail.side}`);
            console.log(`原始数量: ${orderDetail.original_size}`);
            console.log(`已匹配数量: ${orderDetail.size_matched}`);
            console.log(`价格: ${orderDetail.price}`);
            console.log(`结果: ${orderDetail.outcome}`);
            console.log(`创建时间: ${new Date(orderDetail.created_at * 1000).toISOString()}`);
            console.log(`过期时间: ${orderDetail.expiration}`);
            console.log(`订单类型: ${orderDetail.order_type}`);
            
            if (orderDetail.associate_trades && orderDetail.associate_trades.length > 0) {
                console.log(`关联交易数量: ${orderDetail.associate_trades.length}`);
                console.log(`关联交易 IDs: ${orderDetail.associate_trades.join(', ')}`);
            }
            console.log('=========================================\n');

        } catch (apiKeyError) {
            if (apiKeyError.message && apiKeyError.message.includes('API key does not exist')) {
                console.log('⚠️  API Key 不存在，正在创建新的 API Key...');
                
                // 创建新的 API key
                const creds = await clobClient.createApiKey();
                console.log(`✅ API Key 已创建`);
                console.log(`   API Key: ${creds.key.substring(0, 10)}...`);
                console.log(`   Passphrase: ${creds.passphrase.substring(0, 10)}...`);

                // 使用 creds 初始化一个新的 ClobClient 实例用于 L2 认证
                const authenticatedClient = new ClobClient(
                    HOST,
                    CHAIN_ID,
                    wallet,
                    creds
                );

                console.log(`\n正在获取订单详情...`);
                console.log(`   订单 ID: ${orderId}`);

                const orderDetail = await authenticatedClient.getOrder(orderId);

                console.log('\n================ 订单详情 ================');
                console.log(`订单 ID: ${orderDetail.id}`);
                console.log(`状态: ${orderDetail.status}`);
                console.log(`所有者: ${orderDetail.owner}`);
                console.log(`Maker 地址: ${orderDetail.maker_address}`);
                console.log(`市场 ID: ${orderDetail.market}`);
                console.log(`资产 ID: ${orderDetail.asset_id}`);
                console.log(`方向: ${orderDetail.side}`);
                console.log(`原始数量: ${orderDetail.original_size}`);
                console.log(`已匹配数量: ${orderDetail.size_matched}`);
                console.log(`价格: ${orderDetail.price}`);
                console.log(`结果: ${orderDetail.outcome}`);
                console.log(`创建时间: ${new Date(orderDetail.created_at * 1000).toISOString()}`);
                console.log(`过期时间: ${orderDetail.expiration}`);
                console.log(`订单类型: ${orderDetail.order_type}`);
                
                if (orderDetail.associate_trades && orderDetail.associate_trades.length > 0) {
                    console.log(`关联交易数量: ${orderDetail.associate_trades.length}`);
                    console.log(`关联交易 IDs: ${orderDetail.associate_trades.join(', ')}`);
                }
                console.log('=========================================\n');
            } else {
                throw apiKeyError;
            }
        }

    } catch (error) {
        console.error('\n❌ 获取订单详情失败:');
        console.error(error.message);
        
        if (error.response) {
            console.error(`\nHTTP 状态码: ${error.response.status}`);
            console.error(`响应数据:`, error.response.data);
        }
        
        process.exit(1);
    }
}

// 检查命令行参数
const args = process.argv.slice(2);

if (args.length < 2) {
    console.error('错误: 缺少必要参数');
    console.error('\n使用方法:');
    console.error('  node scripts/get-order-detail.js <private_key> <order_id>');
    console.error('\n参数说明:');
    console.error('  private_key: 钱包私钥（用于签名）');
    console.error('  order_id: 订单 ID');
    console.error('\n示例:');
    console.error('  node scripts/get-order-detail.js "0x123..." "0x456..."');
    process.exit(1);
}

const [privateKey, orderId] = args;

// 验证私钥格式
if (!privateKey.startsWith('0x')) {
    console.error('错误: 私钥格式不正确，必须以 0x 开头');
    process.exit(1);
}

if (privateKey.length !== 66) {
    console.error('错误: 私钥长度不正确，应为 66 个字符（包括 0x 前缀）');
    process.exit(1);
}

// 验证订单 ID
if (!orderId.startsWith('0x')) {
    console.error('错误: 订单 ID 格式不正确，必须以 0x 开头');
    process.exit(1);
}

// 执行主函数
getOrderDetail(privateKey, orderId);
