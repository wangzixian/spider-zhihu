package com.netopstec.spiderzhihu.crawler;

import cn.wanghaomiao.seimi.annotation.Crawler;
import cn.wanghaomiao.seimi.def.BaseSeimiCrawler;
import cn.wanghaomiao.seimi.struct.Request;
import cn.wanghaomiao.seimi.struct.Response;
import com.netopstec.spiderzhihu.common.HttpConstants;
import com.netopstec.spiderzhihu.common.RabbitConstants;
import com.netopstec.spiderzhihu.domain.IpProxy;
import com.netopstec.spiderzhihu.service.IpProxyService;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.select.Elements;
import org.seimicrawler.xpath.JXDocument;
import org.seimicrawler.xpath.JXNode;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 爬取可免费代理的服务器IP地址的爬虫类
 * 云免费代理（http://www.ip3366.net/）
 * @author zhenye 2019/7/1
 */
@Slf4j
@Crawler(name = "ipProxy-yun-crawler")
public class IpProxyYunCrawler extends BaseSeimiCrawler {

    @Autowired
    private AmqpTemplate rabbitTemplate;
    @Autowired
    private IpProxyService ipProxyService;

    @Override
    public String getUserAgent() {
        return HttpConstants.refreshMyUserAgent();
    }

    @Override
    public String proxy() {
        IpProxy ipProxy = ipProxyService.getActiveProxyIp();
        if (ipProxy != null) {
            log.info("本次用的代理是: [{}:{}]", ipProxy.getIp(), ipProxy.getPort());
            return ipProxy.getType().toLowerCase() + "://" + ipProxy.getIp() + ":" + ipProxy.getPort();
        }
        log.info("由于没有一个可用的代理IP，因此用的是本机IP。注意可能会被加入黑名单。");
        return super.proxy();
    }
    @Override
    public String[] startUrls() {
        return new String[]{ HttpConstants.YUN_IP_PROXY_URL_PREFIX };
    }

    private static Integer pageNum = 1;

    @Override
    public void start(Response response) {
        log.info("正在爬取免费云代理第{}页的代理IP...", pageNum);
        JXDocument jxDocument = response.document();
        if (jxDocument == null) {
            pageNum++;
            push(Request.build(HttpConstants.YUN_IP_PROXY_URL_PREFIX + "?stype=1&page=" + pageNum, IpProxyYunCrawler::start));
            return;
        }
        JXNode node = jxDocument.selNOne("//*[@id=\"list\"]");
        Elements ipProxyList = node.asElement().child(0).child(1).children();
        for(int i = 0; i < ipProxyList.size(); i++) {
            Elements ipInfo = ipProxyList.get(i).children();
            String proxyIp = ipInfo.get(0).text();
            String proxyPort = ipInfo.get(1).text();
            String proxyAddress = ipInfo.get(5).text();
            String proxyType = ipInfo.get(3).text();
            // https的代理使用以及验证方式不一样，目前不保存这种代理
            if ("HTTPS".equals(proxyType)) {
                continue;
            }
            IpProxy ipProxy = new IpProxy();
            ipProxy.setIp(proxyIp);
            ipProxy.setPort(proxyPort);
            ipProxy.setAddress(proxyAddress);
            ipProxy.setType(proxyType);
            // 将爬取到的代理放到消息队列中
            rabbitTemplate.convertAndSend(RabbitConstants.QUEUE_SAVE_ACTIVE_PROXY_IP_TO_DB, ipProxy);
        }
        if (pageNum < 20) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                log.error("线程阻塞异常");
            }
            pageNum++;
            push(Request.build(HttpConstants.YUN_IP_PROXY_URL_PREFIX + "?stype=1&page=" + pageNum, IpProxyYunCrawler::start));
        } else {
            pageNum = 1;
            log.info("已经爬取完前20页的所有云免费代理");
        }
    }
}